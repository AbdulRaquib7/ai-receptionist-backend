package com.ai.receptionist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Externalized configuration for conversation pipeline tuning parameters.
 * All values have sensible defaults matching the original hardcoded values.
 * Override via application.properties or environment variables.
 */
@Configuration
@ConfigurationProperties(prefix = "conversation")
public class ConversationProperties {

    /** Consecutive silent frames before end-of-speech (~20ms each). 16 ≈ 320ms. */
    private int silenceFrameThreshold = 16;

    /** Minimum audio buffer size (bytes) before silence-triggered processing */
    private int minAudioBytes = 16000;

    /** Maximum audio buffer size (bytes) — triggers processing even without silence */
    private int maxBufferBytes = 64000;

    /** Average byte energy below which a frame is silent (0–255 scale) */
    private int silenceEnergyThreshold = 10;

    /** Number of recent conversation messages to keep for context summary */
    private int recentMessageWindow = 6;

    /** LLM temperature (0.0 = deterministic, 1.0 = creative) */
    private double llmTemperature = 0.2;

    /** Number of days ahead to look for available appointment slots */
    private int slotLookAheadDays = 7;

    /** Maximum number of conversation messages to send to the LLM per request */
    private int maxLlmHistoryMessages = 20;

    // --- Getters and Setters ---

    public int getSilenceFrameThreshold() { return silenceFrameThreshold; }
    public void setSilenceFrameThreshold(int silenceFrameThreshold) { this.silenceFrameThreshold = silenceFrameThreshold; }

    public int getMinAudioBytes() { return minAudioBytes; }
    public void setMinAudioBytes(int minAudioBytes) { this.minAudioBytes = minAudioBytes; }

    public int getMaxBufferBytes() { return maxBufferBytes; }
    public void setMaxBufferBytes(int maxBufferBytes) { this.maxBufferBytes = maxBufferBytes; }

    public int getSilenceEnergyThreshold() { return silenceEnergyThreshold; }
    public void setSilenceEnergyThreshold(int silenceEnergyThreshold) { this.silenceEnergyThreshold = silenceEnergyThreshold; }

    public int getRecentMessageWindow() { return recentMessageWindow; }
    public void setRecentMessageWindow(int recentMessageWindow) { this.recentMessageWindow = recentMessageWindow; }

    public double getLlmTemperature() { return llmTemperature; }
    public void setLlmTemperature(double llmTemperature) { this.llmTemperature = llmTemperature; }

    public int getSlotLookAheadDays() { return slotLookAheadDays; }
    public void setSlotLookAheadDays(int slotLookAheadDays) { this.slotLookAheadDays = slotLookAheadDays; }

    public int getMaxLlmHistoryMessages() { return maxLlmHistoryMessages; }
    public void setMaxLlmHistoryMessages(int maxLlmHistoryMessages) { this.maxLlmHistoryMessages = maxLlmHistoryMessages; }
}
