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
        List<Doctor> doctors = appointmentService.getAllDoctors();
        context.append("DOCTORS:\n");
        for (Doctor d : doctors) {
            context.append("- ").append(d.getKey()).append(": ").append(d.getName()).append("\n");
        }

        Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
        context.append("\nAVAILABLE SLOTS (date -> times):\n");
        slots.forEach((docKey, byDate) -> {
            context.append(docKey).append(": ").append(byDate.toString()).append("\n");
        });

        Optional<AppointmentService.AppointmentSummary> existing = StringUtils.isNotBlank(fromNumber)
                ? appointmentService.getActiveAppointmentSummary(fromNumber)
                : Optional.empty();
        if (existing.isPresent()) {
            AppointmentService.AppointmentSummary a = existing.get();
            context.append("\nCALLER'S EXISTING APPOINTMENT: ").append(a.doctorName)
                    .append(" on ").append(a.slotDate)
                    .append(" at ").append(a.startTime).append("\n");
        } else {
            context.append("\nCALLER HAS NO EXISTING APPOINTMENT.\n");
        }

        context.append("\nRULES:\n");
        context.append("- You are a clinic voice receptionist. Keep responses SHORT (1-2 sentences) for voice.\n");
        context.append("- Book flow: 1) Get doctor + date + time, 2) Ask for name and phone, 3) Confirm. ALWAYS ask for name and phone before confirming.\n");
        context.append("- Doctor keys: dr-ahmed (9am-12pm), dr-john (12pm-2pm), dr-evening (6pm-10pm). Time formats: 10:30 AM, 12:00 PM, etc.\n");
        context.append("- ONLY use doctors and slots from the data above. Never invent slots. If slot unavailable, suggest from AVAILABLE SLOTS.\n");
        context.append("- When user asks for available times, list the exact times from AVAILABLE SLOTS for that doctor and date.\n");

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
