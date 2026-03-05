package com.ai.receptionist.service;

import com.ai.receptionist.dto.PendingActionDto;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.entity.Doctor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * LLM as flow controller: generates natural replies and optionally a pending action
 * when asking the user to confirm (book/cancel/reschedule). Backend executes only
 * after confirmation; this service never writes to the database.
 */
@Service
@RequiredArgsConstructor
public class LlmFlowService {

    private static final Logger log = LoggerFactory.getLogger(LlmFlowService.class);

    private final RestTemplate restTemplate = new RestTemplateBuilder().build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppointmentService appointmentService;

    @Value("${openai.api-key:}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String openAiModel;

    @Value("${caller.anonymous-fallback:+100000000}")
    private String anonymousCallerFallback;

    public static class FlowResponse {
        private final String message;
        private final PendingActionDto action;

        public FlowResponse(String message, PendingActionDto action) {
            this.message = message;
            this.action = action;
        }

        public String getMessage() { return message; }
        public PendingActionDto getAction() { return action; }
    }

    /**
     * Build context and call LLM. Returns natural-language message and optional
     * pending action (when LLM is asking user to confirm book/cancel/reschedule).
     * Slots and doctors are fetched dynamically from DB; never hardcoded.
     */
    public FlowResponse generateReply(String callSid, String fromNumber, List<ChatMessage> history) {
        if (StringUtils.isBlank(openAiApiKey)) {
            log.error("OPENAI_API_KEY is not set");
            return new FlowResponse("I'm having a quick technical moment. Can you say that again?", null);
        }

        String context = buildContext(fromNumber);
        String outputFormat = buildOutputFormatInstructions();

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", context + "\n\n" + outputFormat));

        for (ChatMessage msg : history) {
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", openAiModel != null ? openAiModel : "gpt-4o-mini");
        body.put("temperature", 0.2);
        body.put("messages", messages);
        // Force the model to return valid JSON so parsing is reliable.
        body.put("response_format", Map.of("type", "json_object"));

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(openAiApiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "https://api.openai.com/v1/chat/completions",
                    new HttpEntity<>(body, headers),
                    String.class
            );
            JsonNode root = mapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
            log.info("LLM RAW RESPONSE for call {}: {}", callSid, content);
            FlowResponse parsed = parseStructuredResponse(content);
            if (parsed.getAction() != null) {
                log.info("LlmFlowService: structured action present for call {} -> intent={}",
                        callSid, parsed.getAction().getIntent());
            } else {
                log.info("LlmFlowService: no structured action returned for call {}", callSid);
            }
            return parsed;
        } catch (Exception ex) {
            log.error("LlmFlowService: LLM call failed", ex);
            return new FlowResponse("Sorry, I didn't catch that. Could you repeat?", null);
        }
    }

    private String buildContext(String fromNumber) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        StringBuilder ctx = new StringBuilder();
        ctx.append("TODAY'S DATE: ").append(today).append(". Use for \"today\", \"tomorrow\", etc.\n");
        ctx.append("CURRENT TIME (24h, clinic local time): ")
                .append(java.time.LocalTime.now().withSecond(0).withNano(0).toString())
                .append(". Use this to decide if today's remaining slots are still valid.\n\n");
        ctx.append("DATABASE STATE (single source of truth; slots from appointment_slot WHERE status=AVAILABLE):\n\n");

        List<Doctor> doctors = appointmentService.getAllDoctors();
        ctx.append("DOCTORS:\n");
        for (Doctor d : doctors) {
            ctx.append("- key: ").append(d.getKey()).append(", name: ").append(d.getName());
            if (StringUtils.isNotBlank(d.getSpecialization())) {
                ctx.append(", specialization: ").append(d.getSpecialization());
            }
            ctx.append("\n");
        }

        Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
        ctx.append("\nAVAILABLE SLOTS (dynamic; never invent):\n");
        slots.forEach((docKey, byDate) -> {
            ctx.append(docKey).append(": ");
            List<String> parts = new ArrayList<>();
            byDate.forEach((date, times) -> {
                if (times != null && !times.isEmpty()) {
                    parts.add(date + " " + LlmService.formatSlotsAsRanges(times));
                }
            });
            ctx.append(String.join("; ", parts)).append("\n");
        });

        String resolvedPhone = resolveCallerForLookup(fromNumber);
        List<AppointmentService.AppointmentSummary> appointments =
                StringUtils.isNotBlank(resolvedPhone)
                        ? appointmentService.getUpcomingAppointmentSummaries(resolvedPhone)
                        : List.of();
        if (!appointments.isEmpty()) {
            ctx.append("\nCALLER'S UPCOMING APPOINTMENTS: ");
            for (int i = 0; i < appointments.size(); i++) {
                AppointmentService.AppointmentSummary a = appointments.get(i);
                if (i > 0) ctx.append(" | ");
                ctx.append(a.patientName).append(" with ").append(a.doctorName)
                        .append(" on ").append(a.slotDate).append(" at ").append(a.startTime);
            }
            ctx.append("\n");
        } else {
            ctx.append("\nCALLER HAS NO UPCOMING APPOINTMENTS.\n");
        }

        ctx.append("\nVOICE & RULES:\n");
        ctx.append("- You are a real human receptionist. Short, warm, conversational. No robotic phrases.\n");
        ctx.append("- Primary language is English. Always reply in English. If the caller speaks another language (Spanish, Hindi, etc.), reply in English and politely ask them to repeat in English (for example: \"Could you please say that in English so I can help you properly?\").\n");
        ctx.append("- Answer general questions briefly, then return to flow: \"Now, about your appointment…\". Never book or cancel an appointment for pure general questions.\n");
        ctx.append("- When offering times, prefer slots for TODAY and TOMORROW only. Use AVAILABLE SLOTS above as the single source of truth; never invent times.\n");
        ctx.append("- For TODAY, ignore any times that are earlier than the CURRENT TIME. If all of today's slots are already in the past, say that today is fully booked and offer TOMORROW and the day after (using the actual future slots from the list).\n");
        ctx.append("- AVAILABLE SLOTS are exact truth. If a specific time appears in AVAILABLE SLOTS for that doctor and date, you MUST treat it as available and you must NOT say it is unavailable. If the user asks for a time that does NOT appear in AVAILABLE SLOTS, explain it is not available and offer nearby times or other days from the list.\n");
        ctx.append("- BOOK: suggest doctor → read slot options from the list (grouped today/tomorrow) → collect name & phone → ask confirmation.\n");
        ctx.append("- CANCEL/RESCHEDULE: use caller's upcoming appointments above; confirm which one; ask confirmation.\n");
        ctx.append("- Phone number: if the contact number sounds incomplete, missing digits, or unclear, politely ask the caller again for the full number and confirm it before proceeding to book.\n");
        ctx.append("- CONFIRMATION & ACTIONS: Whenever your message asks the caller to confirm a specific booking/cancel/reschedule (for example: \"Should I go ahead and book that?\", \"Would you like to reschedule that one?\"), you MUST include the \"action\" block in your JSON with the correct intent and all known details. This is the ONLY time you set a non-null action.\n");
        ctx.append("- Pending decisions and hangup: If you have just proposed a specific booking/cancel/reschedule and are waiting for a yes/no, and the caller says they want to end the call (e.g. \"bye\", \"see ya\", \"that's all\", \"hang up\"), do NOT end the call immediately. First, respond in English like: \"Before we end the call, do you want me to [book/cancel/reschedule] it? Say yes to confirm, or no to end the call.\" and include the same action object again. Only after the caller clearly answers yes or no should you either proceed with the action or end with the goodbye.\n");
        ctx.append("- Goodbye: \"Thanks for calling. Take care!\" Only when there are no pending booking/cancel/reschedule decisions and the user clearly wants to end the call.\n");
        ctx.append("- Unclear: \"Sorry, I didn't catch that. Could you repeat?\"\n");
        return ctx.toString();
    }

    private String buildOutputFormatInstructions() {
        return "OUTPUT FORMAT: You MUST reply with ONLY a single JSON object. No markdown, no code fences, no text before or after the JSON. The user will hear only the \"message\" value — never output raw JSON in the message.\n"
                + "{\"message\": \"your natural reply here\", \"action\": null}\n"
                + "When asking user to CONFIRM a booking, set action to (time must be 12-hour with AM/PM, e.g. 07:00 PM not 19:00):\n"
                + "{\"intent\": \"BOOK\", \"doctorKey\": \"<key from DOCTORS>\", \"date\": \"YYYY-MM-DD\", \"time\": \"07:00 PM\", \"patientName\": \"...\", \"patientPhone\": \"...\"}\n"
                + "When asking to CONFIRM cancel: {\"intent\": \"CANCEL\", \"targetPatientName\": \"<patient name from CALLER'S UPCOMING APPOINTMENTS, not the doctor>\"}\n"
                + "When asking to CONFIRM reschedule: {\"intent\": \"RESCHEDULE\", \"targetPatientName\": \"<patient name>\", \"doctorKey\": \"...\", \"newDate\": \"YYYY-MM-DD\", \"newTime\": \"07:00 PM\"}\n"
                + "Use exact doctorKey, date and time from the context. If not asking for confirmation, set \"action\" to null. Never concatenate JSON after the message text — output only the one JSON object.";
    }

    private String resolveCallerForLookup(String fromNumber) {
        if (fromNumber == null || fromNumber.isBlank()
                || fromNumber.startsWith("client:")
                || "anonymous".equalsIgnoreCase(fromNumber.trim())
                || "unknown".equalsIgnoreCase(fromNumber.trim())) {
            String fallback = StringUtils.isNotBlank(anonymousCallerFallback)
                    ? anonymousCallerFallback.trim()
                    : "+100000000";
            return fallback;
        }
        return fromNumber;
    }

    /**
     * Parses LLM output. Handles: (1) Valid {"message":"...","action":{...}},
     * (2) Natural text with trailing JSON (e.g. "...book that?{\"intent\":\"BOOK\",...}"),
     * (3) Action-only trailing object. The returned message is always speech-safe (no raw JSON).
     */
    private FlowResponse parseStructuredResponse(String content) {
        if (StringUtils.isBlank(content)) {
            return new FlowResponse("I didn't catch that. Could you repeat?", null);
        }
        content = content.trim();
        if (content.startsWith("```")) {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) content = content.substring(start, end + 1);
        }

        // Try full-object parse from first {
        int firstBrace = content.indexOf('{');
        if (firstBrace >= 0) {
            try {
                JsonNode root = mapper.readTree(content.substring(firstBrace));
                if (root.isObject() && root.has("message")) {
                    String message = root.get("message").asText("").trim();
                    if (message.isEmpty()) message = "Could you say that again?";
                    PendingActionDto action = parseActionNode(root.path("action"));
                    return new FlowResponse(speechSafeMessage(message), action);
                }
            } catch (Exception ignored) { /* fall through to trailing-JSON handling */ }
        }

        // Try last JSON object (trailing action or malformed full response)
        int lastBrace = content.lastIndexOf('{');
        if (lastBrace >= 0) {
            try {
                int matchEnd = findMatchingBrace(content, lastBrace);
                if (matchEnd > lastBrace) {
                    String jsonPart = content.substring(lastBrace, matchEnd + 1);
                    JsonNode node = mapper.readTree(jsonPart);
                    if (node.isObject()) {
                        if (node.has("message") && node.has("action")) {
                            String message = node.get("message").asText("").trim();
                            if (message.isEmpty()) message = "Could you say that again?";
                            return new FlowResponse(speechSafeMessage(message), parseActionNode(node.path("action")));
                        }
                        if (node.has("intent")) {
                            String textBefore = content.substring(0, lastBrace).trim();
                            PendingActionDto action = parseActionFromNode(node);
                            if (action != null) {
                                String message = textBefore.isEmpty() ? "Could you say that again?" : speechSafeMessage(textBefore);
                                return new FlowResponse(message, action);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse trailing JSON: {}", e.getMessage());
            }
        }

        String safe = speechSafeMessage(firstBrace >= 0 ? content.substring(0, firstBrace).trim() : content);
        if (safe.isEmpty()) safe = "Could you say that again?";
        return new FlowResponse(safe, null);
    }

    private int findMatchingBrace(String s, int from) {
        if (from < 0 || from >= s.length() || s.charAt(from) != '{') return -1;
        int depth = 1;
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** Removes any trailing JSON or raw action from message so it is never spoken. */
    private static String speechSafeMessage(String message) {
        if (message == null || message.isEmpty()) return message;
        int i = message.indexOf('{');
        if (i >= 0) {
            String before = message.substring(0, i).trim();
            if (!before.isEmpty()) return before;
        }
        return message.trim();
    }

    private PendingActionDto parseActionFromNode(JsonNode actionNode) {
        if (actionNode == null || !actionNode.isObject()) return null;
        String intentStr = actionNode.path("intent").asText("");
        if (StringUtils.isBlank(intentStr)) return null;
        try {
            PendingActionDto.Intent intent = PendingActionDto.Intent.valueOf(intentStr.toUpperCase());
            PendingActionDto action = PendingActionDto.builder()
                    .intent(intent)
                    .doctorKey(nullIfEmpty(actionNode.path("doctorKey").asText()))
                    .date(nullIfEmpty(actionNode.path("date").asText()))
                    .time(normalizeTimeFromLlm(nullIfEmpty(actionNode.path("time").asText())))
                    .patientName(nullIfEmpty(actionNode.path("patientName").asText()))
                    .patientPhone(nullIfEmpty(actionNode.path("patientPhone").asText()))
                    .targetPatientName(nullIfEmpty(actionNode.path("targetPatientName").asText()))
                    .newDate(nullIfEmpty(actionNode.path("newDate").asText()))
                    .newTime(normalizeTimeFromLlm(nullIfEmpty(actionNode.path("newTime").asText())))
                    .awaitingConfirmation(true)
                    .build();
            log.info("Parsed LLM action: intent={} doctorKey={} date={} time={} patientName={} targetPatient={} newDate={} newTime={}",
                    intent, action.getDoctorKey(), action.getDate(), action.getTime(),
                    action.getPatientName(), action.getTargetPatientName(), action.getNewDate(), action.getNewTime());
            return action;
        } catch (IllegalArgumentException e) {
            log.warn("Unknown intent in LLM action: {}", intentStr);
            return null;
        }
    }

    private PendingActionDto parseActionNode(JsonNode actionNode) {
        if (actionNode == null || actionNode.isNull() || !actionNode.isObject()) return null;
        return parseActionFromNode(actionNode);
    }

    /** Normalizes LLM time (e.g. 19:00 or 7 PM) to 12h format for DB (e.g. 07:00 PM). */
    private static String normalizeTimeFromLlm(String time) {
        if (time == null || time.isBlank()) return time;
        String t = time.trim().replace('.', ':');
        if (t.matches("\\d{1,2}:\\d{2}\\s*(AM|PM)")) return t;
        if (t.matches("\\d{1,2}:\\d{2}")) {
            int h = Integer.parseInt(t.split(":")[0]);
            String m = t.split(":")[1];
            if (h >= 12) return String.format("%02d:%s PM", h == 12 ? 12 : h - 12, m);
            return String.format("%02d:%s AM", h == 0 ? 12 : h, m);
        }
        return t;
    }

    private static String nullIfEmpty(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
