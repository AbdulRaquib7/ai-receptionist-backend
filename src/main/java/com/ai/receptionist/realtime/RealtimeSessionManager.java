package com.ai.receptionist.realtime;

import com.ai.receptionist.entity.CallSession;
import com.ai.receptionist.service.AppointmentService;
import com.ai.receptionist.service.CallSessionService;
import com.ai.receptionist.service.TenantService;
import com.ai.receptionist.entity.Appointment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Per-call orchestrator for the OpenAI Realtime API pipeline.
 * Bridges Twilio Media Stream audio (mu-law 8kHz) ↔ Realtime API (PCM16 24kHz).
 *
 * One instance per active call. Created by MediaStreamHandler when a stream
 * starts for a realtime-enabled tenant.
 *
 * Responsibilities:
 * - Convert and forward audio bidirectionally
 * - Execute function calls (book/cancel/reschedule) and return results
 * - Handle barge-in (cancel AI response when user speaks)
 * - Track call session lifecycle (in-progress, outcome, complete)
 * - Enforce session timeout
 */
public class RealtimeSessionManager implements RealtimeEventHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSessionManager.class);

    private final String callSid;
    private final String fromNumber;
    private final Long tenantId;
    private final WebSocketSession twilioSession;
    private RealtimeApiClient realtimeClient;
    private final RealtimeSessionConfig sessionConfig;
    private final AppointmentService appointmentService;
    private final CallSessionService callSessionService;
    private final TenantService tenantService;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final long sessionTimeoutMs;

    private ScheduledFuture<?> timeoutFuture;
    private volatile boolean closed = false;
    private volatile boolean aiSpeaking = false;
    private String streamSid; // Twilio stream SID for sending audio back

    public RealtimeSessionManager(String callSid,
                                   String fromNumber,
                                   Long tenantId,
                                   WebSocketSession twilioSession,
                                   RealtimeApiClient realtimeClient,
                                   RealtimeSessionConfig sessionConfig,
                                   AppointmentService appointmentService,
                                   CallSessionService callSessionService,
                                   TenantService tenantService,
                                   ObjectMapper mapper,
                                   ScheduledExecutorService scheduler,
                                   long sessionTimeoutMs) {
        this.callSid = callSid;
        this.fromNumber = fromNumber;
        this.tenantId = tenantId;
        this.twilioSession = twilioSession;
        this.realtimeClient = realtimeClient;
        this.sessionConfig = sessionConfig;
        this.appointmentService = appointmentService;
        this.callSessionService = callSessionService;
        this.tenantService = tenantService;
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.sessionTimeoutMs = sessionTimeoutMs;
    }

    /**
     * Initialize the session: connect to Realtime API, send session config, start timeout.
     */
    public void start() {
        try {
            // Connect to the Realtime API (blocks until handshake completes)
            realtimeClient.connectBlocking(10, TimeUnit.SECONDS);

            if (!realtimeClient.isOpen()) {
                log.error("[{}] Failed to connect to Realtime API", callSid);
                return;
            }

            // Send session configuration (system prompt, tools, VAD)
            Map<String, Object> config = sessionConfig.buildSessionConfig(tenantId, fromNumber);
            realtimeClient.sendSessionUpdate(config);

            // Mark call as in-progress and tag pipeline
            callSessionService.markInProgress(callSid);
            callSessionService.setPipelineTag(callSid, "REALTIME_API");

            // Start session timeout
            timeoutFuture = scheduler.schedule(this::onTimeout, sessionTimeoutMs, TimeUnit.MILLISECONDS);

            log.info("[{}] Realtime session started for tenant={}", callSid, tenantId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[{}] Interrupted while connecting to Realtime API", callSid);
        } catch (Exception e) {
            log.error("[{}] Failed to start realtime session", callSid, e);
        }
    }

    /**
     * Process an inbound audio frame from Twilio (mu-law 8kHz).
     * Converts to PCM16 24kHz and forwards to the Realtime API.
     */
    public void handleTwilioAudio(byte[] mulawBytes) {
        if (closed || !realtimeClient.isOpen()) return;

        // Convert: mu-law 8kHz → PCM16 8kHz → PCM16 24kHz
        byte[] pcm16_8k = AudioFormatUtil.mulawToPcm16(mulawBytes);
        byte[] pcm16_24k = AudioFormatUtil.pcm16_8kTo24k(pcm16_8k);

        realtimeClient.sendAudioChunk(pcm16_24k);
    }

    /**
     * Close the realtime session and clean up resources.
     */
    public void close() {
        if (closed) return;
        closed = true;

        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }

        if (realtimeClient.isOpen()) {
            realtimeClient.close();
        }

        callSessionService.completeCall(callSid);
        log.info("[{}] Realtime session closed", callSid);
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Set the Realtime API client. Called after construction to break
     * the circular dependency (client needs this as handler, this needs client).
     */
    public void setRealtimeClient(RealtimeApiClient client) {
        this.realtimeClient = client;
    }

    /**
     * Set the Twilio stream SID for sending audio back.
     */
    public void setStreamSid(String streamSid) {
        this.streamSid = streamSid;
    }

    // --- RealtimeEventHandler callbacks ---

    @Override
    public void onAudioDelta(byte[] pcm16Bytes) {
        if (closed) return;
        aiSpeaking = true;

        try {
            // Convert: PCM16 24kHz → PCM16 8kHz → mu-law 8kHz
            byte[] pcm16_8k = AudioFormatUtil.pcm16_24kTo8k(pcm16Bytes);
            byte[] mulaw = AudioFormatUtil.pcm16ToMulaw(pcm16_8k);
            String base64 = AudioFormatUtil.toBase64(mulaw);

            // Send to Twilio as a media message
            String mediaMessage = mapper.writeValueAsString(Map.of(
                    "event", "media",
                    "streamSid", streamSid != null ? streamSid : "",
                    "media", Map.of("payload", base64)
            ));

            if (twilioSession.isOpen()) {
                twilioSession.sendMessage(new TextMessage(mediaMessage));
            }
        } catch (Exception e) {
            log.error("[{}] Error sending audio to Twilio", callSid, e);
        }
    }

    @Override
    public void onFunctionCall(String functionName, String argumentsJson, String callId) {
        log.info("[{}] Executing function: {} args={}", callSid, functionName, argumentsJson);

        String result;
        try {
            JsonNode args = mapper.readTree(argumentsJson);
            result = switch (functionName) {
                case "book_appointment" -> executeBookAppointment(args);
                case "cancel_appointment" -> executeCancelAppointment(args);
                case "reschedule_appointment" -> executeRescheduleAppointment(args);
                default -> {
                    log.warn("[{}] Unknown function: {}", callSid, functionName);
                    yield "{\"error\": \"Unknown function: " + functionName + "\"}";
                }
            };
        } catch (Exception e) {
            log.error("[{}] Function execution failed: {}", callSid, functionName, e);
            result = "{\"error\": \"Function execution failed: " + e.getMessage() + "\"}";
        }

        // Return result to the Realtime API
        realtimeClient.sendFunctionCallOutput(callId, result);
    }

    @Override
    public void onResponseDone() {
        aiSpeaking = false;
        log.debug("[{}] AI response complete", callSid);
    }

    @Override
    public void onSpeechStarted() {
        // Barge-in: user started speaking while AI is talking
        if (aiSpeaking) {
            log.info("[{}] Barge-in detected — cancelling AI response", callSid);
            realtimeClient.cancelResponse();
            // Send clear message to Twilio to stop playback
            sendTwilioClear();
            aiSpeaking = false;
        }
    }

    @Override
    public void onSpeechStopped() {
        log.debug("[{}] User speech stopped (VAD)", callSid);
    }

    @Override
    public void onError(String errorMessage) {
        log.error("[{}] Realtime API error: {}", callSid, errorMessage);
    }

    @Override
    public void onSessionClosed() {
        log.info("[{}] Realtime API session closed remotely", callSid);
        close();
    }

    // --- Function execution ---

    private String executeBookAppointment(JsonNode args) {
        String patientName = args.path("patient_name").asText("");
        String patientPhone = args.path("patient_phone").asText("");
        String doctorKey = args.path("doctor_key").asText("");
        String date = args.path("date").asText("");
        String time = args.path("time").asText("");

        String resolvedPhone = patientPhone.isBlank() ? fromNumber : patientPhone;

        Optional<Appointment> result = appointmentService.bookAppointment(
                tenantId, resolvedPhone, patientName, resolvedPhone, doctorKey, date, time);

        if (result.isPresent()) {
            callSessionService.setOutcome(callSid, CallSession.Outcome.BOOKED);
            return "{\"success\": true, \"message\": \"Appointment booked successfully for " +
                    patientName + " with " + doctorKey + " on " + date + " at " + time + "\"}";
        } else {
            return "{\"success\": false, \"message\": \"Could not book appointment. The slot may no longer be available.\"}";
        }
    }

    private String executeCancelAppointment(JsonNode args) {
        String patientName = args.path("patient_name").asText(null);

        boolean cancelled = appointmentService.cancelAppointment(tenantId, fromNumber, patientName);

        if (cancelled) {
            callSessionService.setOutcome(callSid, CallSession.Outcome.CANCELLED);
            return "{\"success\": true, \"message\": \"Appointment cancelled successfully.\"}";
        } else {
            return "{\"success\": false, \"message\": \"No upcoming appointment found to cancel.\"}";
        }
    }

    private String executeRescheduleAppointment(JsonNode args) {
        String patientName = args.path("patient_name").asText(null);
        String doctorKey = args.path("doctor_key").asText("");
        String newDate = args.path("new_date").asText("");
        String newTime = args.path("new_time").asText("");

        Optional<Appointment> result = appointmentService.rescheduleAppointment(
                tenantId, fromNumber, patientName, doctorKey, newDate, newTime);

        if (result.isPresent()) {
            callSessionService.setOutcome(callSid, CallSession.Outcome.RESCHEDULED);
            return "{\"success\": true, \"message\": \"Appointment rescheduled to " +
                    doctorKey + " on " + newDate + " at " + newTime + "\"}";
        } else {
            return "{\"success\": false, \"message\": \"Could not reschedule. The new slot may not be available.\"}";
        }
    }

    // --- Helpers ---

    private void onTimeout() {
        log.warn("[{}] Realtime session timed out after {}ms", callSid, sessionTimeoutMs);
        close();
    }

    private void sendTwilioClear() {
        try {
            String clearMessage = mapper.writeValueAsString(Map.of(
                    "event", "clear",
                    "streamSid", streamSid != null ? streamSid : ""
            ));
            if (twilioSession.isOpen()) {
                twilioSession.sendMessage(new TextMessage(clearMessage));
            }
        } catch (Exception e) {
            log.error("[{}] Error sending clear to Twilio", callSid, e);
        }
    }
}
