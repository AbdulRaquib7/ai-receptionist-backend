package com.ai.receptionist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the OpenAI Realtime API pipeline.
 * Per-tenant opt-in via tenant.use_realtime_api flag.
 * Global kill-switch via converge.realtime.enabled=false.
 */
@Configuration
@ConfigurationProperties(prefix = "converge.realtime")
public class RealtimeApiProperties {

    /** Global kill-switch. When false, no tenant uses Realtime API regardless of DB flag. */
    private boolean enabled = false;

    /** OpenAI Realtime model name */
    private String model = "gpt-4o-realtime-preview";

    /** Default voice for Realtime API responses */
    private String voice = "alloy";

    /** WebSocket URL for the Realtime API */
    private String apiUrl = "wss://api.openai.com/v1/realtime";

    /** Server-side VAD: milliseconds of silence before turn end is declared */
    private int vadSilenceDurationMs = 500;

    /** Server-side VAD sensitivity (0.0 = least sensitive, 1.0 = most sensitive) */
    private double vadThreshold = 0.5;

    /** Audio padding before speech detected (ms) */
    private int vadPrefixPaddingMs = 300;

    /** Max session duration in milliseconds (default: 5 min) */
    private long sessionTimeoutMs = 300000;

    // --- Getters and Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

    public int getVadSilenceDurationMs() { return vadSilenceDurationMs; }
    public void setVadSilenceDurationMs(int vadSilenceDurationMs) { this.vadSilenceDurationMs = vadSilenceDurationMs; }

    public double getVadThreshold() { return vadThreshold; }
    public void setVadThreshold(double vadThreshold) { this.vadThreshold = vadThreshold; }

    public int getVadPrefixPaddingMs() { return vadPrefixPaddingMs; }
    public void setVadPrefixPaddingMs(int vadPrefixPaddingMs) { this.vadPrefixPaddingMs = vadPrefixPaddingMs; }

    public long getSessionTimeoutMs() { return sessionTimeoutMs; }
    public void setSessionTimeoutMs(long sessionTimeoutMs) { this.sessionTimeoutMs = sessionTimeoutMs; }
}
