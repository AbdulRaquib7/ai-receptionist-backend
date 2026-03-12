package com.ai.receptionist.service;

import com.ai.receptionist.exception.TtsException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Map;

/**
 * Text-to-speech via ElevenLabs API. Uses credentials from application properties.
 * Does not modify call flow; callers use the returned audio (e.g. serve via URL for Twilio &lt;Play&gt;).
 */
@Service
public class ElevenLabsVoiceService {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsVoiceService.class);
    private static final String TTS_URL = "https://api.elevenlabs.io/v1/text-to-speech/%s";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${elevenlabs.api-key}")
    private String apiKey;

    @Value("${elevenlabs.voice-id}")
    private String voiceId;

    public ElevenLabsVoiceService(@Qualifier("ttsRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Synthesize speech from text. Returns MP3 bytes.
     * Throws TtsException on failure so callers can fall back to Twilio's built-in TTS.
     */
    @Retryable(
            retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
            noRetryFor = {HttpClientErrorException.class},
            maxAttempts = 2,
            backoff = @Backoff(delay = 300)
    )
    public byte[] synthesize(String text) {
        if (text == null || text.isBlank()) {
            return new byte[0];
        }

        // Trim to handle whitespace/quotes from env vars
        String key = apiKey != null ? apiKey.trim() : "";
        if (key.isEmpty()) {
            throw new TtsException("ElevenLabs api-key not set", null);
        }

        String vid = voiceId != null ? voiceId.trim() : "21m00Tcm4TlvDq8ikWAM";
        String url = String.format(TTS_URL, vid);

        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "text", text,
                    "model_id", "eleven_multilingual_v2"
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.set("xi-api-key", key);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.List.of(MediaType.parseMediaType("audio/mpeg")));

            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<byte[]> response = restTemplate.exchange(url,
                    HttpMethod.POST,
                    request,
                    byte[].class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.debug("ElevenLabs TTS OK, bytes={}", response.getBody().length);
                return response.getBody();
            }
            throw new TtsException("ElevenLabs returned non-2xx status", null);
        } catch (HttpClientErrorException e) {
            log.error("ElevenLabs client error {}: {} | key prefix: {}...",
                    e.getStatusCode(), e.getResponseBodyAsString(),
                    key.substring(0, Math.min(8, key.length())));
            throw new TtsException("TTS authentication/client error: " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new TtsException("TTS service unreachable", e);
        } catch (HttpServerErrorException e) {
            throw new TtsException("TTS server error: " + e.getStatusCode(), e);
        } catch (TtsException e) {
            throw e;
        } catch (Exception e) {
            throw new TtsException("TTS synthesis failed", e);
        }
    }
}
