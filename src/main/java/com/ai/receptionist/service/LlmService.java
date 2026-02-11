package com.ai.receptionist.service;

import com.ai.receptionist.entity.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.ai.receptionist.entity.Doctor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates replies using OpenAI Chat Completions.
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppointmentService appointmentService;

    @Value("${openai.api-key}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String openAiModel;

    public LlmService(RestTemplateBuilder builder, AppointmentService appointmentService) {
        this.restTemplate = builder.build();
        this.appointmentService = appointmentService;
    }

    public String generateReply(String callSid, String fromNumber, List<ChatMessage> history) {
        if (StringUtils.isBlank(openAiApiKey)) {
            log.error("OPENAI_API_KEY is not set");
            return "";
        }

        String url = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openAiApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        StringBuilder context = new StringBuilder();
        context.append("DATABASE STATE (fetch dynamically - this is the single source of truth):\n\n");

        List<Doctor> doctors = appointmentService.getAllDoctors();
        context.append("DOCTORS (from doctor table):\n");
        for (Doctor d : doctors) {
            context.append("- key: ").append(d.getKey())
                    .append(", name: ").append(d.getName());
            if (d.getSpecialization() != null && !d.getSpecialization().isBlank()) {
                context.append(", specialization: ").append(d.getSpecialization());
            }
            context.append("\n");
        }

        Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
        context.append("\nAVAILABLE SLOTS (from appointment_slot where status=AVAILABLE):\n");
        slots.forEach((docKey, byDate) -> {
            context.append(docKey).append(": ").append(byDate.toString()).append("\n");
        });

        List<AppointmentService.AppointmentSummary> appointments = StringUtils.isNotBlank(fromNumber)
                ? appointmentService.getActiveAppointmentSummaries(fromNumber)
                : List.of();
        if (!appointments.isEmpty()) {
            context.append("\nCALLER'S APPOINTMENT(S): ");
            for (int i = 0; i < appointments.size(); i++) {
                AppointmentService.AppointmentSummary a = appointments.get(i);
                if (i > 0) context.append(" | ");
                context.append(a.patientName).append(" with ").append(a.doctorName)
                        .append(" on ").append(a.slotDate).append(" at ").append(a.startTime);
            }
            context.append("\n");
        } else {
            context.append("\nCALLER HAS NO EXISTING APPOINTMENT.\n");
        }

        context.append("\nPERSONALITY & TONE:\n");
        context.append("- Sound like a warm, friendly HUMAN receptionist—not a robot. Use natural, conversational language.\n");
        context.append("- Vary phrasing. Say \"Sure thing!\" or \"Of course!\" instead of always \"Okay.\" Use contractions (I'm, that's, we've).\n");
        context.append("- Be empathetic: \"No problem,\" \"I understand,\" \"Let me help you with that.\"\n");
        context.append("- Keep responses SHORT (1-2 sentences) for voice—no long lists or formal scripts.\n");
        context.append("\nGENERAL QUESTIONS:\n");
        context.append("- If the user asks something off-topic (weather, time, chitchat, \"how are you\", \"what's your name\", etc.), answer warmly and briefly, then gently steer back: \"Is there anything I can help you with for appointments today?\" or \"What can I do for you?\"\n");
        context.append("- Never refuse. Answer the question, then offer to help with appointments.\n");
        context.append("\nCRITICAL RULES:\n");
        context.append("- You already know the caller's phone number from Twilio. NEVER ask for phone verification.\n");
        context.append("- ONLY use doctor names from the DOCTORS list above. Never hallucinate.\n");
        context.append("- When listing slots: use ranges (e.g. 6 to 9 PM) and mention only 2-3 nearest.\n");
        context.append("- Book: choose doctor, slots, name, confirm. Cancel/reschedule: identify by caller, confirm.\n");
        context.append("- If slot unavailable: politely suggest another time. Don't repeat all slots.\n");
        context.append("- If user wants to hang up mid-booking, ask once: \"Sure, shall I end the call?\"\n");

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", context.toString());
        messages.add(systemMsg);

        for (ChatMessage msg : history) {
            Map<String, String> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            messages.add(m);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", openAiModel);
        body.put("temperature", 0.2);
        body.put("messages", messages);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            JsonNode root = mapper.readTree(response.getBody());
            return root.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (Exception ex) {
            log.error("Failed to get LLM reply", ex);
            return "";
        }
    }
}
