package com.ai.receptionist.component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Short-lived cache for TTS audio bytes awaiting Twilio playback.
 * Entries auto-expire after 5 minutes to prevent memory leaks from
 * audio clips that are never fetched (e.g. dropped calls).
 */
@Component
public class AudioPlaybackCache {

    /** TTL-bounded cache: max 200 clips, auto-expire after 5 minutes */
    private final Cache<String, byte[]> cache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

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
        if (id == null) return null;
        byte[] audio = cache.getIfPresent(id);
        if (audio != null) {
            cache.invalidate(id);
        }
        return audio;
    }
}
