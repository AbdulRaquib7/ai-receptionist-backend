package com.ai.receptionist.websocket;

import com.ai.receptionist.config.ConversationProperties;
import com.ai.receptionist.config.RealtimeApiProperties;
import com.ai.receptionist.entity.Tenant;
import com.ai.receptionist.realtime.RealtimeApiClient;
import com.ai.receptionist.realtime.RealtimeSessionConfig;
import com.ai.receptionist.realtime.RealtimeSessionManager;
import com.ai.receptionist.service.AppointmentService;
import com.ai.receptionist.service.CallSessionService;
import com.ai.receptionist.service.ConversationOrchestrator;
import com.ai.receptionist.service.ReminderCallContextService;
import com.ai.receptionist.service.TenantService;
import com.ai.receptionist.utils.LogSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final TenantService tenantService;

    // Realtime API dependencies
    private final RealtimeApiProperties realtimeProps;
    private final RealtimeSessionConfig realtimeSessionConfig;
    private final AppointmentService appointmentService;
    private final ReminderCallContextService reminderCallContextService;
    private final ScheduledExecutorService realtimeScheduler;

    @Value("${openai.api-key}")
    private String openAiApiKey;

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
                              CallSessionService callSessionService,
                              TenantService tenantService,
                              RealtimeApiProperties realtimeProps,
                              RealtimeSessionConfig realtimeSessionConfig,
                              AppointmentService appointmentService,
                              ReminderCallContextService reminderCallContextService) {
        this.orchestrator = orchestrator;
        this.mapper = objectMapper;
        this.conversationProps = conversationProps;
        this.voicePipelineExecutor = voicePipelineExecutor;
        this.callSessionService = callSessionService;
        this.tenantService = tenantService;
        this.realtimeProps = realtimeProps;
        this.realtimeSessionConfig = realtimeSessionConfig;
        this.appointmentService = appointmentService;
        this.reminderCallContextService = reminderCallContextService;
        this.realtimeScheduler = Executors.newScheduledThreadPool(2,
                r -> { Thread t = new Thread(r, "realtime-scheduler"); t.setDaemon(true); return t; });
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
        long firstFrameTimestamp = 0;   // epoch millis of first audio frame (for latency measurement)
        // Per-tenant overrides (resolved at stream start, -1 = use global default)
        int tenantSilenceFrameThreshold = -1;
        int tenantSilenceEnergyThreshold = -1;
        // Realtime API pipeline fields
        boolean realtimeMode = false;
        RealtimeSessionManager realtimeSession;
        String streamSid; // needed for sending audio back to Twilio
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
            // Outbound reminder: CalleePhone = patient we called, AppointmentId for marking reminded
            String calleePhone = customParams.path("CalleePhone").asText("");
            if (!calleePhone.isBlank()) {
                state.fromNumber = calleePhone;
                callFromNumbers.put(state.callSid, calleePhone);
            }
            String appointmentIdParam = customParams.path("AppointmentId").asText("");
            if (!appointmentIdParam.isBlank()) {
                try {
                    Long appointmentId = Long.parseLong(appointmentIdParam);
                    reminderCallContextService.setReminderContext(state.callSid, appointmentId);
                } catch (NumberFormatException ignored) {}
            }
            // Resolve per-tenant silence tuning overrides (if configured in tenant_config table)
            if (state.tenantId != null && tenantService != null) {
                String frameThresholdStr = tenantService.getConfig(state.tenantId, "silence_frame_threshold", "");
                String energyThresholdStr = tenantService.getConfig(state.tenantId, "silence_energy_threshold", "");
                if (!frameThresholdStr.isBlank()) {
                    try { state.tenantSilenceFrameThreshold = Integer.parseInt(frameThresholdStr); }
                    catch (NumberFormatException ignored) {}
                }
                if (!energyThresholdStr.isBlank()) {
                    try { state.tenantSilenceEnergyThreshold = Integer.parseInt(energyThresholdStr); }
                    catch (NumberFormatException ignored) {}
                }
            }
            state.streamSid = streamSid;
            streams.put(streamSid, state);

            // Check if tenant uses the Realtime API pipeline
            boolean useRealtime = false;
            if (state.tenantId != null) {
                try {
                    Tenant tenant = tenantService.findById(state.tenantId);
                    if (tenant != null) {
                        useRealtime = tenantService.isRealtimeApiEnabled(tenant);
                    }
                } catch (Exception e) {
                    log.debug("Could not check realtime flag for tenant {}: {}", state.tenantId, e.getMessage());
                }
            }
            state.realtimeMode = useRealtime;

            log.info("========== STREAM START ==========");
            log.info("Stream SID : {}", streamSid);
            log.info("Call SID   : {}", state.callSid);
            log.info("From       : {}", state.fromNumber.isBlank() ? "(test/anonymous)" : LogSanitizer.maskPhone(state.fromNumber));
            log.info("Tenant ID  : {}", state.tenantId);
            log.info("Pipeline   : {}", state.realtimeMode ? "REALTIME_API" : "LEGACY");
            if (state.tenantSilenceFrameThreshold > 0 || state.tenantSilenceEnergyThreshold > 0) {
                log.info("Silence tuning (tenant override): frameThreshold={} energyThreshold={}",
                        state.tenantSilenceFrameThreshold > 0 ? state.tenantSilenceFrameThreshold : "default",
                        state.tenantSilenceEnergyThreshold > 0 ? state.tenantSilenceEnergyThreshold : "default");
            }
            log.info("==================================");

            // Initialize Realtime API session if enabled
            if (state.realtimeMode) {
                initRealtimeSession(state, session);
            }
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
                // Close realtime session if active
                if (stopped.realtimeMode && stopped.realtimeSession != null) {
                    stopped.realtimeSession.close();
                }
                boolean hasOtherStreams = streams.asMap().values().stream()
                        .anyMatch(s -> stopped.callSid.equals(s.callSid));
                if (!hasOtherStreams) {
                    callFromNumbers.invalidate(stopped.callSid);
                    // Mark reminder call completed if this was an outbound reminder (user hung up)
                    reminderCallContextService.getAndClear(stopped.callSid)
                            .ifPresent(appointmentId -> appointmentService.markReminded(appointmentId));
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
            log.warn("payload null");
            return;
        }

        // Guard against oversized payloads (~75KB decoded)
        if (payload.length() > 100_000) {
            log.warn("Oversized media payload ({}), skipping", payload.length());
            return;
        }

        byte[] frame = Base64.getDecoder().decode(payload);

        // Realtime API pipeline: forward raw mu-law audio directly
        if (state.realtimeMode && state.realtimeSession != null && !state.realtimeSession.isClosed()) {
            state.realtimeSession.handleTwilioAudio(frame);
            return;
        }

        state.framesReceived++;
        if (state.firstFrameTimestamp == 0) {
            state.firstFrameTimestamp = System.currentTimeMillis();
        }

        int energy = 0;
        for (byte b : frame) energy += Math.abs(b);
        energy /= frame.length;

        state.buffer.write(frame, 0, frame.length);

        // Use per-tenant thresholds if configured, otherwise fall back to global defaults
        int energyThreshold = state.tenantSilenceEnergyThreshold > 0
                ? state.tenantSilenceEnergyThreshold
                : conversationProps.getSilenceEnergyThreshold();
        int frameThreshold = state.tenantSilenceFrameThreshold > 0
                ? state.tenantSilenceFrameThreshold
                : conversationProps.getSilenceFrameThreshold();

        boolean silent = energy < energyThreshold;
        if (silent) state.silenceFrames++;
        else state.silenceFrames = 0;

        int size = state.buffer.size();

        log.debug("frame#={} size={} energy={} silenceFrames={} buffer={} energyThreshold={} frameThreshold={}",
                state.framesReceived, frame.length, energy, state.silenceFrames, size, energyThreshold, frameThreshold);

        boolean silenceTrigger =
                state.silenceFrames >= frameThreshold
                        && size >= conversationProps.getMinAudioBytes();

        boolean overflowTrigger = size >= conversationProps.getMaxBufferBytes();

        if ((silenceTrigger || overflowTrigger) && !state.processing) {

            byte[] utterance = state.buffer.toByteArray();
            state.buffer.reset();
            state.silenceFrames = 0;
            state.processing = true;

            long elapsedMs = System.currentTimeMillis() - state.firstFrameTimestamp;
            log.info("🎤 SPEECH DETECTED | bytes={} frames={} frameThreshold={} silenceTrigger={} overflow={} elapsedMs={}",
                    utterance.length, state.framesReceived, frameThreshold,
                    silenceTrigger, overflowTrigger, elapsedMs);
            state.firstFrameTimestamp = 0; // reset for next utterance

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
                log.error("PIPELINE ERROR for call {}", callSid, e);
            } finally {
                state.processing = false;
            }
        }, voicePipelineExecutor)
        .orTimeout(30, TimeUnit.SECONDS)
        .exceptionally(ex -> {
            log.error("Pipeline timed out or failed for call {}", callSid, ex);
            state.processing = false;
            return null;
        });
    }

    /**
     * Initialize a Realtime API session for a stream.
     * Creates the WebSocket client, session manager, and connects asynchronously.
     */
    private void initRealtimeSession(StreamState state, WebSocketSession twilioSession) {
        String apiUrl = realtimeProps.getApiUrl() + "?model=" + realtimeProps.getModel();

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(
                state.callSid,
                state.fromNumber,
                state.tenantId,
                twilioSession,
                null, // client set below
                realtimeSessionConfig,
                appointmentService,
                callSessionService,
                tenantService,
                mapper,
                realtimeScheduler,
                realtimeProps.getSessionTimeoutMs()
        );

        RealtimeApiClient client = new RealtimeApiClient(apiUrl, openAiApiKey, sessionManager, state.callSid);
        // Inject the client and streamSid into the session manager
        sessionManager.setRealtimeClient(client);
        sessionManager.setStreamSid(state.streamSid);
        state.realtimeSession = sessionManager;

        // Connect and configure asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                sessionManager.start();
                log.info("[{}] Realtime session initialized", state.callSid);
            } catch (Exception e) {
                log.error("[{}] Failed to initialize realtime session, falling back to legacy", state.callSid, e);
                state.realtimeMode = false;
                state.realtimeSession = null;
            }
        }, voicePipelineExecutor);
    }
}
