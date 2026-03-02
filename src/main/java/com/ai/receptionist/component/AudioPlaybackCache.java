package com.ai.receptionist.component;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived cache for generated TTS audio so Twilio can play it via &lt;Play&gt; URL.
 * Entries are removed after retrieval to avoid unbounded growth.
 */
@Component
public class AudioPlaybackCache {

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /**
     * Store audio bytes and return a unique playback id for the URL.
     */
    public String put(byte[] audio) {
        if (audio == null || audio.length == 0) return null;
        String id = UUID.randomUUID().toString();
        cache.put(id, audio);
        return id;
    }

    /**
     * Retrieve and remove audio for the given id. Returns null if missing or already consumed.
     */
    public byte[] take(String id) {
        return id == null ? null : cache.remove(id);
    }
}
