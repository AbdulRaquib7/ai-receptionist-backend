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
            return parseStructuredResponse(content);
        } catch (Exception ex) {
            log.error("LlmFlowService: LLM call failed", ex);
            return new FlowResponse("Sorry, I didn't catch that. Could you repeat?", null);
        }
    }

    private String buildContext(String fromNumber) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        StringBuilder ctx = new StringBuilder();
        ctx.append("TODAY'S DATE: ").append(today).append(". Use for \"today\", \"tomorrow\", etc.\n\n");
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
        ctx.append("- Answer general questions briefly, then return to flow: \"Now, about your appointment…\"\n");
        ctx.append("- BOOK: suggest doctor → slots (from list above) → name & phone → ask confirmation.\n");
        ctx.append("- CANCEL/RESCHEDULE: use caller's appointments above; confirm which one; ask confirmation.\n");
        ctx.append("- Only when you ask user to CONFIRM (e.g. \"Should I go ahead and book that?\") include the \"action\" block in your JSON.\n");
        ctx.append("- Goodbye: \"Thanks for calling. Take care!\" Only when user clearly says bye.\n");
        ctx.append("- Unclear: \"Sorry, I didn't catch that. Could you repeat?\"\n");
        return ctx.toString();
    }

    private String buildOutputFormatInstructions() {
        return "OUTPUT FORMAT: Reply with ONLY a JSON object. No markdown, no extra text.\n"
                + "{\"message\": \"your natural reply here\", \"action\": null}\n"
                + "When asking user to CONFIRM a booking, set action to:\n"
                + "{\"intent\": \"BOOK\", \"doctorKey\": \"<key from DOCTORS>\", \"date\": \"YYYY-MM-DD\", \"time\": \"06:00 PM\", \"patientName\": \"...\", \"patientPhone\": \"...\"}\n"
                + "When asking to CONFIRM cancel: {\"intent\": \"CANCEL\", \"targetPatientName\": \"...\"}\n"
                + "When asking to CONFIRM reschedule: {\"intent\": \"RESCHEDULE\", \"targetPatientName\": \"...\", \"doctorKey\": \"...\", \"newDate\": \"YYYY-MM-DD\", \"newTime\": \"06:00 PM\"}\n"
                + "Use exact doctorKey, date and time from the context. If not asking for confirmation, set \"action\" to null.";
    }

    private String resolveCallerForLookup(String fromNumber) {
        if (fromNumber == null || fromNumber.isBlank()) return null;
        if (fromNumber.startsWith("client:") || "anonymous".equalsIgnoreCase(fromNumber)) return null;
        return fromNumber;
    }

    private FlowResponse parseStructuredResponse(String content) {
        if (StringUtils.isBlank(content)) {
            return new FlowResponse("I didn't catch that. Could you repeat?", null);
        }
        content = content.trim();
        // Strip markdown code block if present
        if (content.startsWith("```")) {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) content = content.substring(start, end + 1);
        }
        int start = content.indexOf('{');
        if (start < 0) {
            return new FlowResponse(content, null);
        }
        try {
            JsonNode root = mapper.readTree(content.substring(start));
            String message = root.has("message") ? root.get("message").asText("").trim() : content;
            if (message.isEmpty()) message = "Could you say that again?";

            JsonNode actionNode = root.path("action");
            PendingActionDto action = null;
            if (actionNode != null && !actionNode.isNull() && actionNode.isObject()) {
                String intentStr = actionNode.path("intent").asText("");
                if (StringUtils.isNotBlank(intentStr)) {
                    try {
                        PendingActionDto.Intent intent = PendingActionDto.Intent.valueOf(intentStr.toUpperCase());
                        action = PendingActionDto.builder()
                                .intent(intent)
                                .doctorKey(nullIfEmpty(actionNode.path("doctorKey").asText()))
                                .date(nullIfEmpty(actionNode.path("date").asText()))
                                .time(nullIfEmpty(actionNode.path("time").asText()))
                                .patientName(nullIfEmpty(actionNode.path("patientName").asText()))
                                .patientPhone(nullIfEmpty(actionNode.path("patientPhone").asText()))
                                .targetPatientName(nullIfEmpty(actionNode.path("targetPatientName").asText()))
                                .newDate(nullIfEmpty(actionNode.path("newDate").asText()))
                                .newTime(nullIfEmpty(actionNode.path("newTime").asText()))
                                .awaitingConfirmation(true)
                                .build();
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown intent in LLM action: {}", intentStr);
                    }
                }
            }
            return new FlowResponse(message, action);
        } catch (Exception e) {
            log.warn("Failed to parse LLM JSON, using raw message: {}", e.getMessage());
            return new FlowResponse(content, null);
        }
    }

    private static String nullIfEmpty(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
