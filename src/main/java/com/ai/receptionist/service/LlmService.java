package com.ai.receptionist.service;

import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.entity.ConversationState;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates replies using OpenAI Chat Completions.
 * Supports structured context (doctor list, slots) to prevent hallucination.
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private static final String BASE_SYSTEM_PROMPT =
            "You are a concise, friendly medical receptionist. You help with doctor appointment booking. " +
            "Data comes from: doctor table (only isActive=true doctors), appointment_slot table (only AVAILABLE slots). " +
            "When booking: patient name and contact save to patient table; slot status changes to BOOKED. " +
            "You ONLY use the provided doctors and slots. Never invent data. Keep answers suitable for phone/voice.";

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${openai.api-key:${OPENAI_API_KEY:}}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String openAiModel;

    public LlmService(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    @jakarta.annotation.PostConstruct
    private void init() {
        if (openAiApiKey != null) openAiApiKey = openAiApiKey.trim();
        if (StringUtils.isBlank(openAiApiKey) || !openAiApiKey.startsWith("sk-")) {
            log.warn("OpenAI API key missing or invalid format. 401 errors will occur.");
        }
    }

    public String generateReply(List<ChatMessage> history) {
        return generateReplyWithContext(history, null);
    }

    /**
     * Generate reply with conversation state and structured context.
     * Context is injected into the system prompt to prevent hallucination.
     */
    public String generateReplyWithContext(List<ChatMessage> history, LlmContext context) {
        if (StringUtils.isBlank(openAiApiKey)) {
            log.error("OPENAI_API_KEY is not set");
            return "";
        }

        String url = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openAiApiKey != null ? openAiApiKey.trim() : "");
        headers.setContentType(MediaType.APPLICATION_JSON);

        List<Map<String, String>> messages = new ArrayList<>();

        String systemContent = buildSystemPrompt(context);
        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemContent);
        messages.add(systemMsg);

        for (ChatMessage msg : history) {
            Map<String, String> message = new HashMap<>();
            message.put("role", msg.getRole());
            message.put("content", msg.getContent());
            messages.add(message);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", openAiModel);
        body.put("temperature", 0.1);
        body.put("messages", messages);

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            JsonNode root = mapper.readTree(response.getBody());
            return root.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (Exception ex) {
            log.error("Failed to get LLM reply", ex);
            return "I'm sorry, I'm having a technical issue. Please try again in a moment.";
        }
    }

    private String buildSystemPrompt(LlmContext ctx) {
        StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT);
        if (ctx != null) {
            if (ctx.stateInstructions() != null && !ctx.stateInstructions().isEmpty()) {
                sb.append("\n\n").append(ctx.stateInstructions());
            }
            sb.append("\n\nCurrent state: ").append(ctx.state());
            if (ctx.doctorListText() != null && !ctx.doctorListText().isEmpty()) {
                sb.append("\n\nAVAILABLE DOCTORS - When caller asks for doctors, names, or list, you MUST say: ")
                  .append(ctx.doctorListText())
                  .append("\nNEVER refuse to list doctors. You have the list.");
            }
            if (ctx.slotListText() != null && !ctx.slotListText().isEmpty()) {
                sb.append("\n\nUse ONLY these available slots when offering times:\n").append(ctx.slotListText());
            }
        }
        return sb.toString();
    }

    /**
     * Structured context for LLM to prevent hallucination.
     */
    public record LlmContext(
            ConversationState state,
            String stateInstructions,
            String doctorListText,
            String slotListText
    ) {
        public static LlmContext of(ConversationState state) {
            return new LlmContext(state, null, null, null);
        }
        public static LlmContext of(ConversationState state, String instructions) {
            return new LlmContext(state, instructions, null, null);
        }
    }
}
