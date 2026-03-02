package com.ai.receptionist.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Text-to-speech via ElevenLabs API. Uses credentials from application properties.
 * Does not modify call flow; callers use the returned audio (e.g. serve via URL for Twilio &lt;Play&gt;).
 */
@Service
public class ElevenLabsVoiceService {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsVoiceService.class);
    private static final String TTS_URL = "https://api.elevenlabs.io/v1/text-to-speech/%s";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${elevenlabs.api-key:}")
    private String apiKey;

    @Value("${elevenlabs.voice-id:21m00Tcm4TlvDq8ikWAM}")
    private String voiceId;

    public ElevenLabsVoiceService(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    /**
     * Synthesize speech from text. Returns MP3 bytes or empty array on failure.
     */
    public byte[] synthesize(String text) {
        if (text == null || text.isBlank()) {
            return new byte[0];
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ElevenLabs api-key not set — skipping TTS");
            return new byte[0];
        }
        String url = String.format(TTS_URL, voiceId != null ? voiceId : "21m00Tcm4TlvDq8ikWAM");
        HttpHeaders headers = new HttpHeaders();
        headers.set("xi-api-key", apiKey);
        headers.setContentType(MediaType.parseMediaType(CONTENT_TYPE_JSON));
        headers.setAccept(java.util.List.of(MediaType.parseMediaType("audio/mpeg")));

        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("model_id", "eleven_multilingual_v2");

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    byte[].class
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.debug("ElevenLabs TTS OK, bytes={}", response.getBody().length);
                return response.getBody();
            }
        } catch (Exception e) {
            log.error("ElevenLabs TTS failed: {}", e.getMessage());
        }
        return new byte[0];
    }
}
