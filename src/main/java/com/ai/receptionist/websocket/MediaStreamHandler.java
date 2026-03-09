package com.ai.receptionist.websocket;

import com.ai.receptionist.config.ConversationProperties;
import com.ai.receptionist.service.CallSessionService;
import com.ai.receptionist.service.ConversationOrchestrator;
import com.ai.receptionist.utils.LogSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import org.springframework.beans.factory.annotation.Qualifier;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

/**
 * Twilio Media Stream WebSocket handler.
 * Responsibilities: WebSocket lifecycle, audio buffering, silence detection,
 * and dispatching complete utterances to {@link ConversationOrchestrator}.
 */
@Component
public class MediaStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MediaStreamHandler.class);

    private final ObjectMapper mapper;
    private final ConversationProperties conversationProps;
    private final ExecutorService voicePipelineExecutor;
    private final CallSessionService callSessionService;

    /** TTL-bounded cache: max 500 active streams, auto-expire after 1 hour of inactivity */
    private final Cache<String, StreamState> streams = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    /** Persists callSid -> fromNumber across multiple stream segments within the same call */
    private final Cache<String, String> callFromNumbers = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    private final ConversationOrchestrator orchestrator;

    public MediaStreamHandler(ConversationOrchestrator orchestrator,
                              ObjectMapper objectMapper,
                              ConversationProperties conversationProps,
                              @Qualifier("voicePipelineExecutor") ExecutorService voicePipelineExecutor,
                              CallSessionService callSessionService) {
        this.orchestrator = orchestrator;
        this.mapper = objectMapper;
        this.conversationProps = conversationProps;
        this.voicePipelineExecutor = voicePipelineExecutor;
        this.callSessionService = callSessionService;
    }

    static class StreamState {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int silenceFrames = 0;
        volatile boolean processing = false;
        volatile boolean closed = false;
        String callSid;
        String fromNumber = "";
        Long tenantId;
        long framesReceived = 0;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        JsonNode root = mapper.readTree(message.getPayload());
        String event = root.path("event").asText();
        String streamSid = root.path("streamSid").asText();

        if ("start".equals(event)) {
            StreamState state = new StreamState();
            state.callSid = root.path("start").path("callSid").asText("");
            // Extract caller's phone number passed as a custom parameter from TwiML
            JsonNode customParams = root.path("start").path("customParameters");
            String fromParam = customParams.path("From").asText("");
            if (!fromParam.isBlank()) {
                state.fromNumber = fromParam;
                callFromNumbers.put(state.callSid, fromParam);
            } else {
                // Reuse fromNumber from a previous stream segment of the same call
                String cached = callFromNumbers.getIfPresent(state.callSid);
                state.fromNumber = cached != null ? cached : "";
            }
            // Extract tenantId passed from VoiceController
            String tenantIdParam = customParams.path("TenantId").asText("");
            if (!tenantIdParam.isBlank()) {
                try { state.tenantId = Long.parseLong(tenantIdParam); } catch (NumberFormatException ignored) {}
            }
            streams.put(streamSid, state);

            log.info("========== STREAM START ==========");
            log.info("Stream SID : {}", streamSid);
            log.info("Call SID   : {}", state.callSid);
            log.info("From       : {}", state.fromNumber.isBlank() ? "(test/anonymous)" : LogSanitizer.maskPhone(state.fromNumber));
            log.info("Tenant ID  : {}", state.tenantId);
            log.info("==================================");
            return;
        }

        if ("media".equals(event)) {
            handleMedia(streamSid, root);
            return;
        }

        if ("stop".equals(event)) {
            log.info("STREAM STOP: {}", streamSid);
            StreamState stopped = streams.getIfPresent(streamSid);
            streams.invalidate(streamSid);
            if (stopped != null) {
                boolean hasOtherStreams = streams.asMap().values().stream()
                        .anyMatch(s -> stopped.callSid.equals(s.callSid));
                if (!hasOtherStreams) {
                    callFromNumbers.invalidate(stopped.callSid);
                    // Complete the call session if not already completed (e.g. user hung up)
                    callSessionService.completeCall(stopped.callSid);
                }
            }
        }
    }

    private void handleMedia(String streamSid, JsonNode root) {

        StreamState state = streams.getIfPresent(streamSid);
        if (state == null || state.closed) return;

        String payload = root.path("media").path("payload").asText(null);
        if (payload == null) {
            log.warn("⚠ payload null");
            return;
        }

        // Guard against oversized payloads (~75KB decoded)
        if (payload.length() > 100_000) {
            log.warn("⚠ Oversized media payload ({}), skipping", payload.length());
            return;
        }

        byte[] frame = Base64.getDecoder().decode(payload);
        state.framesReceived++;

        int energy = 0;
        for (byte b : frame) energy += Math.abs(b);
        energy /= frame.length;

        state.buffer.write(frame, 0, frame.length);

        boolean silent = energy < conversationProps.getSilenceEnergyThreshold();
        if (silent) state.silenceFrames++;
        else state.silenceFrames = 0;

        int size = state.buffer.size();

        log.debug("frame#={} size={} energy={} silenceFrames={} buffer={}",
                state.framesReceived, frame.length, energy, state.silenceFrames, size);

        boolean silenceTrigger =
                state.silenceFrames >= conversationProps.getSilenceFrameThreshold()
                        && size >= conversationProps.getMinAudioBytes();

        boolean overflowTrigger = size >= conversationProps.getMaxBufferBytes();

        if ((silenceTrigger || overflowTrigger) && !state.processing) {

            byte[] utterance = state.buffer.toByteArray();
            state.buffer.reset();
            state.silenceFrames = 0;
            state.processing = true;

            log.info("🎤 SPEECH DETECTED | bytes={} silenceTrigger={} overflow={}",
                    utterance.length, silenceTrigger, overflowTrigger);

            processUtteranceAsync(utterance, state);
        }
    }

    private void processUtteranceAsync(byte[] audio, StreamState state) {

        String callSid = state.callSid;
        String fromNumber = state.fromNumber;
        Long tenantId = state.tenantId;

        CompletableFuture.runAsync(() -> {
            try {
                orchestrator.processUtterance(callSid, fromNumber, tenantId, audio,
                        endedCallSid -> callFromNumbers.invalidate(endedCallSid));
            } catch (Exception e) {
                log.error("❌ PIPELINE ERROR for call {}", callSid, e);
            } finally {
                state.processing = false;
            }
        }, voicePipelineExecutor)
        .orTimeout(30, TimeUnit.SECONDS)
        .exceptionally(ex -> {
            log.error("⏱ Pipeline timed out or failed for call {}", callSid, ex);
            state.processing = false;
            return null;
        });
    }
}
