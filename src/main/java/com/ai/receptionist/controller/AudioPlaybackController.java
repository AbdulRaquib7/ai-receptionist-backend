package com.ai.receptionist.controller;

import com.ai.receptionist.component.AudioPlaybackCache;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves cached TTS audio for Twilio &lt;Play&gt;. Used when voice is ElevenLabs.
 */
@RestController
public class AudioPlaybackController {

    private final AudioPlaybackCache playbackCache;

    public AudioPlaybackController(AudioPlaybackCache playbackCache) {
        this.playbackCache = playbackCache;
    }

    @GetMapping(value = "/audio/play/{id}", produces = "audio/mpeg")
    public ResponseEntity<byte[]> play(@PathVariable("id") String id) {
        byte[] audio = playbackCache.take(id);
        if (audio == null || audio.length == 0) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
        headers.setContentLength(audio.length);
        return ResponseEntity.ok().headers(headers).body(audio);
    }
}
