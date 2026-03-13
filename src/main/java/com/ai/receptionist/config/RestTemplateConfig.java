package com.ai.receptionist.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Centralized RestTemplate beans with proper timeouts.
 *
 * Each external service gets a dedicated RestTemplate with timeouts tuned to its
 * expected latency: STT (audio upload → short), LLM (token generation → longer),
 * TTS (synthesis → medium), Twilio (API calls → short).
 */
@Configuration
public class RestTemplateConfig {

    /** Default RestTemplate for LLM and general use — 30s read timeout for token generation */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** STT (Whisper API) — 15s read timeout for audio transcription */
    @Bean("sttRestTemplate")
    public RestTemplate sttRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** TTS (ElevenLabs) — 10s read timeout for speech synthesis */
    @Bean("ttsRestTemplate")
    public RestTemplate ttsRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Twilio API — 10s read timeout for call control operations */
    @Bean("twilioRestTemplate")
    public RestTemplate twilioRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }
}
