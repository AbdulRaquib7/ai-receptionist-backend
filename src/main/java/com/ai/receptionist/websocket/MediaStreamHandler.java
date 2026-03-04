package com.ai.receptionist.websocket;

import com.ai.receptionist.component.ConversationStore;
import com.ai.receptionist.dto.PendingActionDto;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.service.*;
import com.ai.receptionist.utils.YesNoResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

@Component
public class MediaStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MediaStreamHandler.class);

    private static final int SILENCE_FRAMES = 25;
    private static final int MIN_AUDIO_BYTES = 16000;
    private static final int MAX_BUFFER_BYTES = 43000;

    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();
    /** Persists callSid -> fromNumber across multiple stream segments within the same call */
    private final Map<String, String> callFromNumbers = new ConcurrentHashMap<>();

    private final SttService sttService;
    private final TwilioService twilioService;
    private final ConversationStore conversationStore;
    private final LlmFlowService llmFlowService;
    private final PendingActionService pendingActionService;
    private final ConfirmationExecutionService confirmationExecutionService;
    private final YesNoClassifierService yesNoClassifier;

    @Value("${openai.api-key:}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String openAiModel;

    public MediaStreamHandler(
            SttService sttService,
            TwilioService twilioService,
            ConversationStore conversationStore,
            LlmFlowService llmFlowService,
            PendingActionService pendingActionService,
            ConfirmationExecutionService confirmationExecutionService,
            YesNoClassifierService yesNoClassifier) {

        this.sttService = sttService;
        this.twilioService = twilioService;
        this.conversationStore = conversationStore;
        this.llmFlowService = llmFlowService;
        this.pendingActionService = pendingActionService;
        this.confirmationExecutionService = confirmationExecutionService;
        this.yesNoClassifier = yesNoClassifier;
    }

    static class StreamState {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int silenceFrames = 0;
        boolean processing = false;
        boolean closed = false;
        String callSid;
        String fromNumber = "";
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
            String fromParam = root.path("start").path("customParameters").path("From").asText("");
            if (!fromParam.isBlank()) {
                state.fromNumber = fromParam;
                callFromNumbers.put(state.callSid, fromParam);
            } else {
                // Reuse fromNumber from a previous stream segment of the same call
                state.fromNumber = callFromNumbers.getOrDefault(state.callSid, "");
            }
            streams.put(streamSid, state);

            log.info("========== STREAM START ==========");
            log.info("Stream SID : {}", streamSid);
            log.info("Call SID   : {}", state.callSid);
            log.info("From       : {}", state.fromNumber.isBlank() ? "(test/anonymous)" : state.fromNumber);
            log.info("==================================");
            return;
        }

        if ("media".equals(event)) {
            handleMedia(streamSid, root);
            return;
        }

        if ("stop".equals(event)) {
            log.info("STREAM STOP: {}", streamSid);
            StreamState stopped = streams.remove(streamSid);
            // Only clean up the callSid->fromNumber mapping when no more streams exist for this call.
            // Do NOT clear booking pending state here — Twilio reconnects with a new stream segment
            // mid-call after each AI reply, so clearing pending state would break ongoing flows.
            if (stopped != null) {
                boolean hasOtherStreams = streams.values().stream()
                        .anyMatch(s -> stopped.callSid.equals(s.callSid));
                if (!hasOtherStreams) {
                    callFromNumbers.remove(stopped.callSid);
                    // Pending booking state is intentionally NOT cleared here.
                    // It will be cleared when the call truly ends (goodbye detected) or
                    // when the booking/cancel/reschedule flow completes.
                }
            }
        }
    }

    /**
     * Determines if the AI reply signals the end of the call (goodbye/farewell phrases).
     * Used to trigger call hangup after the AI speaks the farewell.
     */
    private boolean isEndCallReply(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        // Explicit farewell markers
        if (lower.contains("have a good day")) return true;
        if (lower.contains("have a great day")) return true;
        if (lower.contains("thanks for calling")) return true;
        if (lower.contains("thank you for calling")) return true;
        if (lower.endsWith("bye.") || lower.endsWith("bye!")) return true;
        if (lower.endsWith("goodbye.") || lower.endsWith("goodbye!")) return true;
        // "take care" + "bye/goodbye" in same reply
        if (lower.contains("take care") && (lower.contains("bye") || lower.contains("goodbye"))) return true;
        // "see you then" + "take care" (booking confirmation farewell)
        if (lower.contains("see you then") && lower.contains("take care")) return true;
        return false;
    }

    private void handleMedia(String streamSid, JsonNode root) {

        StreamState state = streams.get(streamSid);
        if (state == null || state.closed) return;

        String payload = root.path("media").path("payload").asText(null);
        if (payload == null) {
            log.warn("⚠ payload null");
            return;
        }

        byte[] frame = Base64.getDecoder().decode(payload);
        state.framesReceived++;

        int energy = 0;
        for (byte b : frame) energy += Math.abs(b);
        energy /= frame.length;

        state.buffer.write(frame, 0, frame.length);

        boolean silent = energy < 8;
        if (silent) state.silenceFrames++;
        else state.silenceFrames = 0;

        int size = state.buffer.size();

        log.debug("frame#={} size={} energy={} silenceFrames={} buffer={}",
                state.framesReceived, frame.length, energy, state.silenceFrames, size);

        boolean silenceTrigger =
                state.silenceFrames >= SILENCE_FRAMES && size >= MIN_AUDIO_BYTES;

        boolean overflowTrigger = size >= MAX_BUFFER_BYTES;

        if ((silenceTrigger || overflowTrigger) && !state.processing) {

            byte[] utterance = state.buffer.toByteArray();
            state.buffer.reset();
            state.silenceFrames = 0;
            state.processing = true;

            log.info("🎤 SPEECH DETECTED | bytes={} silenceTrigger={} overflow={}",
                    utterance.length, silenceTrigger, overflowTrigger);

            processUtteranceAsync(streamSid, utterance, state);
        }
    }

    private void processUtteranceAsync(String streamSid, byte[] audio, StreamState state) {

        CompletableFuture.runAsync(() -> {
            try {

                log.info("➡ Sending to STT | bytes={}", audio.length);

                String userText = sttService.transcribe(audio);

                if (StringUtils.isBlank(userText)) {
                    log.warn("⚠ STT returned empty text");
                    return;
                }

                String callSid = state.callSid;
                String fromNumber = state.fromNumber;

                log.info("🧑 USER SAID: {}", userText);

                String trimmed = userText.trim();
                conversationStore.appendUser(callSid, fromNumber, trimmed);

                // Resume: history hydrates from conversation_history when in-memory is empty (e.g. stream reconnect)
                List<ChatMessage> history = conversationStore.getHistory(callSid);

                PendingActionDto pending = pendingActionService.getPending(callSid);
                if (pending != null) {
                    log.info("Pending action currently stored for call {}: intent={} awaitingConfirmation={}",
                            callSid,
                            pending.getIntent(),
                            pending.isAwaitingConfirmation());
                }
                YesNoResult yesNo = yesNoClassifier.classify(trimmed);

                String aiText = null;

                // Pending confirmation + user said yes → execute action (backend writes to DB only here)
                if (pending != null && pending.isAwaitingConfirmation() && yesNo == YesNoResult.YES) {
                    log.info("User confirmed pending action with YES for call {}", callSid);
                    Optional<String> executed = confirmationExecutionService.execute(callSid, fromNumber, pending);
                    if (executed.isPresent()) {
                        aiText = executed.get();
                        pendingActionService.clearPending(callSid);
                        log.info("Pending action executed and cleared for call {}", callSid);
                    }
                }

                // Pending + user said no → clear and acknowledge
                if (aiText == null && pending != null && pending.isAwaitingConfirmation() && yesNo == YesNoResult.NO) {
                    pendingActionService.clearPending(callSid);
                    aiText = "No problem. What would you like to do?";
                }

                // Otherwise: LLM drives the flow; may set a new pending action when asking for confirmation
                if (aiText == null) {
                    LlmFlowService.FlowResponse response = llmFlowService.generateReply(callSid, fromNumber, history);
                    aiText = response.getMessage();
                    if (response.getAction() != null) {
                        pendingActionService.setPending(callSid, response.getAction());
                    }
                }

                if (StringUtils.isBlank(aiText)) {
                    log.warn("⚠ Empty AI reply");
                    return;
                }

                log.info("🤖 AI REPLY: {}", aiText);
                conversationStore.appendAssistant(callSid, fromNumber, aiText);

                boolean endCall = isEndCallReply(aiText);

                twilioService.speakResponse(callSid, aiText, endCall);
                if (endCall) {
                    pendingActionService.clearPending(callSid);
                    callFromNumbers.remove(callSid);
                }

            } catch (Exception e) {
                log.error("❌ PIPELINE ERROR", e);
            } finally {
                state.processing = false;
            }
        });
    }
}