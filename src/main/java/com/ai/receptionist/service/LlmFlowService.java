package com.ai.receptionist.service;

import com.ai.receptionist.component.CallerPhoneResolver;
import com.ai.receptionist.config.ConversationProperties;
import com.ai.receptionist.dto.PendingActionDto;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.exception.LlmException;
import com.ai.receptionist.utils.LogSanitizer;
import com.ai.receptionist.utils.SlotFormattingUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
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
public class LlmFlowService {

    private static final Logger log = LoggerFactory.getLogger(LlmFlowService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final AppointmentService appointmentService;
    private final CallerPhoneResolver callerPhoneResolver;
    private final ConversationProperties conversationProps;
    private final PromptService promptService;
    private final TenantService tenantService;
    private final ReminderCallContextService reminderCallContextService;

    public LlmFlowService(@Qualifier("restTemplate") RestTemplate restTemplate,
                           ObjectMapper objectMapper,
                           AppointmentService appointmentService,
                           CallerPhoneResolver callerPhoneResolver,
                           ConversationProperties conversationProps,
                           PromptService promptService,
                           TenantService tenantService,
                           ReminderCallContextService reminderCallContextService) {
        this.restTemplate = restTemplate;
        this.mapper = objectMapper;
        this.appointmentService = appointmentService;
        this.callerPhoneResolver = callerPhoneResolver;
        this.conversationProps = conversationProps;
        this.promptService = promptService;
        this.tenantService = tenantService;
        this.reminderCallContextService = reminderCallContextService;
    }

    @Value("${openai.api-key}")
    private String openAiApiKey;

    @Value("${openai.model}")
    private String openAiModel;

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
    @Retryable(
            retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
            noRetryFor = {HttpClientErrorException.class},
            maxAttempts = 2,
            backoff = @Backoff(delay = 500)
    )
    public FlowResponse generateReply(String callSid, String fromNumber, Long tenantId, List<ChatMessage> history) {
        if (StringUtils.isBlank(openAiApiKey)) {
            throw new LlmException("OPENAI_API_KEY is not set", null);
        }

        boolean isReminderCall = reminderCallContextService.isReminderCall(callSid);
        String context = buildContext(fromNumber, tenantId, isReminderCall);
        String outputFormat = buildOutputFormatInstructions();

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", context + "\n\n" + outputFormat));

        // Limit history to prevent unbounded token growth on long calls
        int maxMessages = conversationProps.getMaxLlmHistoryMessages();
        List<ChatMessage> recentHistory = history.size() > maxMessages
                ? history.subList(history.size() - maxMessages, history.size())
                : history;

        for (ChatMessage msg : recentHistory) {
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", openAiModel != null ? openAiModel : "gpt-4o-mini");
        body.put("temperature", conversationProps.getLlmTemperature());
        body.put("messages", messages);

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
            log.debug("LLM RAW RESPONSE for call {}: {}", callSid, content);
            log.info("LLM response received for call {} [{}]", callSid, LogSanitizer.truncateText(content));
            return parseStructuredResponse(content);
        } catch (HttpClientErrorException e) {
            throw new LlmException("LLM authentication/client error: " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new LlmException("LLM service unreachable", e);
        } catch (HttpServerErrorException e) {
            throw new LlmException("LLM server error: " + e.getStatusCode(), e);
        } catch (Exception ex) {
            throw new LlmException("LLM call failed", ex);
        }
    }

    private String buildContext(String fromNumber, Long tenantId, boolean isReminderCall) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        // Build template variables for {{placeholder}} substitution
        Map<String, String> vars = buildTemplateVariables(tenantId);

        StringBuilder ctx = new StringBuilder();

        if (isReminderCall) {
            ctx.append("REMINDER CALL MODE: This is an outbound reminder call. You already delivered the reminder. ")
                    .append("Do NOT proactively ask if they want to reschedule or cancel. ")
                    .append("If the user has no questions or says no thank you, say a brief goodbye and end the call. ")
                    .append("If the user explicitly asks to reschedule or cancel, help them with that for this specific appointment.\n\n");
        }

        ctx.append("TODAY'S DATE: ").append(today).append(". Use for \"today\", \"tomorrow\", etc.\n\n");

        // Tenant-specific persona (from DB templates)
        String persona = promptService.renderTemplate(tenantId, "system_persona",
                "You are a friendly virtual receptionist. Short, warm, conversational.", vars);
        ctx.append(persona).append("\n\n");

        // Dynamic data: doctors, slots, appointments
        ctx.append("DATABASE STATE (single source of truth; slots from appointment_slot WHERE status=AVAILABLE):\n\n");

        List<Doctor> doctors = appointmentService.getAllDoctors(tenantId);
        ctx.append("DOCTORS:\n");
        for (Doctor d : doctors) {
            ctx.append("- key: ").append(d.getKey()).append(", name: ").append(d.getName());
            if (StringUtils.isNotBlank(d.getSpecialization())) {
                ctx.append(", specialization: ").append(d.getSpecialization());
            }
            ctx.append("\n");
        }

        Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek(tenantId);
        ctx.append("\nAVAILABLE SLOTS (dynamic; never invent):\n");
        ctx.append("IMMEDIATE (Today & Tomorrow - offer first):\n");
        
        LocalDate todayDate = LocalDate.parse(today);
        String tomorrow = todayDate.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        
        slots.forEach((docKey, byDate) -> {
            List<String> immediateParts = new ArrayList<>();
            List<String> laterParts = new ArrayList<>();
            
            byDate.forEach((date, times) -> {
                if (times != null && !times.isEmpty()) {
                    if (date.equals(today) || date.equals(tomorrow)) {
                        String dayLabel = date.equals(today) ? "Today" : "Tomorrow";
                        immediateParts.add(dayLabel + " " + SlotFormattingUtil.formatSlotsAsList(times));
                    } else if (LocalDate.parse(date).isBefore(todayDate.plusDays(7))) {
                        try {
                            String dayName = LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d"));
                            laterParts.add(dayName + " " + SlotFormattingUtil.formatSlotsAsList(times));
                        } catch (Exception e) {
                            laterParts.add(date + " " + SlotFormattingUtil.formatSlotsAsList(times));
                        }
                    }
                }
            });
            
            if (!immediateParts.isEmpty()) {
                ctx.append("  ").append(docKey).append(": ").append(String.join(", ", immediateParts)).append("\n");
            }
        });
        
        ctx.append("\nOTHER WEEK DATES (if caller asks):\n");
        slots.forEach((docKey, byDate) -> {
            List<String> laterParts = new ArrayList<>();
            byDate.forEach((date, times) -> {
                if (times != null && !times.isEmpty()) {
                    if (!date.equals(today) && !date.equals(tomorrow) && LocalDate.parse(date).isBefore(todayDate.plusDays(7))) {
                        try {
                            String dayName = LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d"));
                                laterParts.add(dayName + " " + SlotFormattingUtil.formatSlotsAsList(times));
                        } catch (Exception e) {
                                laterParts.add(date + " " + SlotFormattingUtil.formatSlotsAsList(times));
                        }
                    }
                }
            });
            if (!laterParts.isEmpty()) {
                ctx.append("  ").append(docKey).append(": ").append(String.join(", ", laterParts)).append("\n");
            }
        });

        String resolvedPhone = callerPhoneResolver.resolve(fromNumber);
        List<AppointmentService.AppointmentSummary> appointments =
                StringUtils.isNotBlank(resolvedPhone)
                        ? appointmentService.getUpcomingAppointmentSummaries(resolvedPhone, tenantId)
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

        // Tenant-specific rules (from DB templates)
        String rules = promptService.renderTemplate(tenantId, "system_rules",
                "- Primary language is English. Always reply in English. If the caller speaks another language, politely ask them to repeat in English.\n"
                        + "- Always confirm before executing any action. Backend executes only after explicit user confirmation.\n"
                        + "- Use AVAILABLE SLOTS as the single source of truth. Never invent doctors, dates, or times.\n"
                        + "- When booking: offer only Today & Tomorrow slots first (from IMMEDIATE section). If the caller asks for other days (\"any other\", \"next week\"), then offer from OTHER WEEK DATES.\n"
                        + "- If the caller says they want to hang up mid-process, ask for confirmation to abort. If there is a pending yes/no decision, ask them to confirm the pending action first (yes to proceed, no to end the call).\n"
                        + "- Goodbye only when there is no pending action and the caller clearly wants to end the call.\n",
                vars);
        ctx.append("\n").append(rules).append("\n");

        // Tenant-specific conversation flows (from DB templates)
        String flows = promptService.renderTemplate(tenantId, "system_flows", "", vars);
        if (!flows.isBlank()) {
            ctx.append("\n").append(flows).append("\n");
        }

        return ctx.toString();
    }

    /**
     * Builds the standard template variable map for a tenant.
     */
    private Map<String, String> buildTemplateVariables(Long tenantId) {
        Map<String, String> vars = new HashMap<>();
        if (tenantId != null) {
            String businessName = tenantService.getTenantName(tenantId);
            vars.put("business_name", businessName != null ? businessName : "our office");
            vars.put("ai_name", tenantService.getConfig(tenantId, "ai_name", "Sarah"));
            vars.put("supported_actions", tenantService.getConfig(tenantId, "supported_actions",
                    "book, reschedule, or cancel appointments"));
            vars.put("business_hours", tenantService.getConfig(tenantId, "business_hours", ""));
            vars.put("business_address", tenantService.getConfig(tenantId, "business_address", ""));
        } else {
            vars.put("business_name", "our office");
            vars.put("ai_name", "Sarah");
            vars.put("supported_actions", "book, reschedule, or cancel appointments");
            vars.put("business_hours", "");
            vars.put("business_address", "");
        }
        return vars;
    }

    private String buildOutputFormatInstructions() {
        return "OUTPUT FORMAT: You MUST reply with ONLY a single JSON object. No markdown, no code fences, no text before or after the JSON. The user will hear only the \"message\" value — never output raw JSON in the message.\n"
                + "{\"message\": \"your natural reply here\", \"action\": null}\n"
                + "When asking user to CONFIRM a booking, set action to (time must be 12-hour with AM/PM, e.g. 07:00 PM not 19:00):\n"
                + "{\"intent\": \"BOOK\", \"doctorKey\": \"<key from DOCTORS>\", \"date\": \"YYYY-MM-DD\", \"time\": \"07:00 PM\", \"patientName\": \"...\", \"patientPhone\": \"...\"}\n"
                + "When asking to CONFIRM cancel: {\"intent\": \"CANCEL\", \"targetPatientName\": \"...\"}\n"
                + "When asking to CONFIRM reschedule: {\"intent\": \"RESCHEDULE\", \"targetPatientName\": \"...\", \"doctorKey\": \"...\", \"newDate\": \"YYYY-MM-DD\", \"newTime\": \"07:00 PM\"}\n"
                + "Use exact doctorKey, date and time from the context. If not asking for confirmation, set \"action\" to null. Never concatenate JSON after the message text — output only the one JSON object.";
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
                    LogSanitizer.maskName(action.getPatientName()),
                    LogSanitizer.maskName(action.getTargetPatientName()),
                    action.getNewDate(), action.getNewTime());
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
        String t = time.trim();

        // Normalize "p.m." / "a.m." before converting dots used as time separators.
        // Otherwise replacing '.' globally would corrupt "p.m." into "p:m".
        t = t.replaceAll("(?i)\\bA\\.?M\\.?\\b", "AM");
        t = t.replaceAll("(?i)\\bP\\.?M\\.?\\b", "PM");

        // Only convert dots between digits (e.g. "4.30" -> "4:30"), not every dot in the string.
        t = t.replaceAll("(?<=\\d)\\.(?=\\d{2}\\b)", ":");

        // Also accept forms like "4.0 PM" -> "4:00 PM"
        t = t.replaceAll("(?<=\\d)\\.(?=\\d\\b)", ":0");

        // Common STT mis-hearings: "4 oo" -> "4:00"
        t = t.replaceAll("(?i)\\b(\\d{1,2})\\s*oo\\b", "$1:00");

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

    /**
     * Simple LLM call for generating a post-call summary.
     * No structured output, no action parsing — just plain text summarization.
     */
    public String generateSummary(String summaryPrompt) {
        if (StringUtils.isBlank(openAiApiKey)) {
            log.warn("Cannot generate summary: OPENAI_API_KEY is not set");
            return null;
        }

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", "You are a concise call summarizer. Summarize phone calls in 2-3 sentences."),
                Map.of("role", "user", "content", summaryPrompt)
        );

        Map<String, Object> body = new HashMap<>();
        body.put("model", openAiModel != null ? openAiModel : "gpt-4o-mini");
        body.put("temperature", 0.3);
        body.put("max_tokens", 200);
        body.put("messages", messages);

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
            return root.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (Exception e) {
            log.error("Failed to generate call summary: {}", e.getMessage());
            return null;
        }
    }
}
