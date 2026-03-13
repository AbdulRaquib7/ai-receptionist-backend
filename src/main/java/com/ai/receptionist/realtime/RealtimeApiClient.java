package com.ai.receptionist.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;

/**
 * WebSocket client for the OpenAI Realtime API.
 * Connects to the Realtime API, sends audio/events, and routes incoming events
 * to a {@link RealtimeEventHandler}.
 *
 * Lifecycle: one instance per call. Create → connect() → send audio → close().
 */
public class RealtimeApiClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(RealtimeApiClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RealtimeEventHandler handler;
    private final String callSid; // for logging context

    /**
     * Create a new RealtimeApiClient.
     *
     * @param apiUrl  full WebSocket URL (e.g. wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview)
     * @param apiKey  OpenAI API key (Bearer token)
     * @param handler callback for incoming events
     * @param callSid call identifier for log correlation
     */
    public RealtimeApiClient(String apiUrl, String apiKey, RealtimeEventHandler handler, String callSid) {
        super(URI.create(apiUrl), Map.of(
                "Authorization", "Bearer " + apiKey,
                "OpenAI-Beta", "realtime=v1"
        ));
        this.handler = handler;
        this.callSid = callSid;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("[{}] Realtime API WebSocket connected (status={})", callSid, handshake.getHttpStatus());
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode root = mapper.readTree(message);
            String type = root.path("type").asText("");

            switch (type) {
                case "session.created":
                    log.info("[{}] Realtime session created", callSid);
                    break;

                case "session.updated":
                    log.debug("[{}] Realtime session updated", callSid);
                    break;

                case "response.audio.delta":
                    String audioBase64 = root.path("delta").asText("");
                    if (!audioBase64.isEmpty()) {
                        byte[] pcm16 = AudioFormatUtil.fromBase64(audioBase64);
                        handler.onAudioDelta(pcm16);
                    }
                    break;

                case "response.function_call_arguments.done":
                    String funcName = root.path("name").asText("");
                    String argsJson = root.path("arguments").asText("{}");
                    String callId = root.path("call_id").asText("");
                    log.info("[{}] Function call: name={} callId={}", callSid, funcName, callId);
                    handler.onFunctionCall(funcName, argsJson, callId);
                    break;

                case "response.done":
                    handler.onResponseDone();
                    break;

                case "input_audio_buffer.speech_started":
                    handler.onSpeechStarted();
                    break;

                case "input_audio_buffer.speech_stopped":
                    handler.onSpeechStopped();
                    break;

                case "error":
                    String errorMsg = root.path("error").path("message").asText("Unknown error");
                    String errorCode = root.path("error").path("code").asText("");
                    log.error("[{}] Realtime API error: code={} message={}", callSid, errorCode, errorMsg);
                    handler.onError(errorMsg);
                    break;

                case "response.audio_transcript.delta":
                    // AI speech transcript (useful for logging)
                    log.debug("[{}] AI transcript delta: {}", callSid, root.path("delta").asText(""));
                    break;

                case "response.audio_transcript.done":
                    log.info("[{}] AI transcript: {}", callSid, root.path("transcript").asText(""));
                    break;

                case "conversation.item.input_audio_transcription.completed":
                    log.info("[{}] User transcript: {}", callSid, root.path("transcript").asText(""));
                    break;

                default:
                    log.debug("[{}] Unhandled realtime event: {}", callSid, type);
                    break;
            }
        } catch (Exception e) {
            log.error("[{}] Error processing realtime message", callSid, e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("[{}] Realtime API WebSocket closed: code={} reason={} remote={}", callSid, code, reason, remote);
        handler.onSessionClosed();
    }

    @Override
    public void onError(Exception ex) {
        log.error("[{}] Realtime API WebSocket error", callSid, ex);
        handler.onError(ex.getMessage());
    }

    // --- Outbound message methods ---

    /**
     * Send a session.update event to configure the session (system prompt, tools, VAD, etc.).
     *
     * @param sessionConfig JSON object for the "session" field of the session.update event
     */
    public void sendSessionUpdate(Map<String, Object> sessionConfig) {
        sendEvent("session.update", Map.of("session", sessionConfig));
    }

    /**
     * Append audio to the input buffer. Audio must be PCM16 24kHz base64-encoded.
     *
     * @param pcm16_24kHz raw PCM16 24kHz audio bytes
     */
    public void sendAudioChunk(byte[] pcm16_24kHz) {
        String base64 = AudioFormatUtil.toBase64(pcm16_24kHz);
        sendEvent("input_audio_buffer.append", Map.of("audio", base64));
    }

    /**
     * Commit the current audio buffer, signaling end of user speech.
     */
    public void commitAudio() {
        sendEvent("input_audio_buffer.commit", Map.of());
    }

    /**
     * Clear the input audio buffer (e.g. when barge-in detected).
     */
    public void clearAudioBuffer() {
        sendEvent("input_audio_buffer.clear", Map.of());
    }

    /**
     * Cancel the current in-progress response (for barge-in).
     */
    public void cancelResponse() {
        sendEvent("response.cancel", Map.of());
    }

    /**
     * Send a function call result back to the Realtime API.
     *
     * @param callId  the call_id from the function_call event
     * @param output  the JSON string result to return to the model
     */
    public void sendFunctionCallOutput(String callId, String output) {
        // Add the function call output as a conversation item
        Map<String, Object> item = Map.of(
                "type", "function_call_output",
                "call_id", callId,
                "output", output
        );
        sendEvent("conversation.item.create", Map.of("item", item));
        // Trigger the model to generate a new response
        sendEvent("response.create", Map.of());
    }

    /**
     * Send a raw JSON event to the Realtime API.
     */
    private void sendEvent(String type, Map<String, Object> payload) {
        if (!isOpen()) {
            log.warn("[{}] Cannot send event '{}': WebSocket not open", callSid, type);
            return;
        }
        try {
            Map<String, Object> event = new java.util.HashMap<>(payload);
            event.put("type", type);
            String json = mapper.writeValueAsString(event);
            send(json);
            log.debug("[{}] Sent event: {}", callSid, type);
        } catch (Exception e) {
            log.error("[{}] Failed to send event '{}'", callSid, type, e);
        }
    }
}
