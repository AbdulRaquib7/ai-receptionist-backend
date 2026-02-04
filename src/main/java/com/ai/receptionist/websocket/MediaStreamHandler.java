package com.ai.receptionist.websocket;

import com.ai.receptionist.entity.AppointmentSlot;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.service.BookingFlowService;
import com.ai.receptionist.service.ConversationStore;
import com.ai.receptionist.service.LlmService;
import com.ai.receptionist.service.SttService;
import com.ai.receptionist.service.TwilioService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MediaStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MediaStreamHandler.class);

    private static final int SILENCE_FRAMES = 20;
    private static final int MIN_AUDIO_BYTES = 12000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();

    private final SttService sttService;
    private final LlmService llmService;
    private final TwilioService twilioService;
    private final ConversationStore conversationStore;
    private final BookingFlowService bookingFlowService;

    public MediaStreamHandler(
            SttService sttService,
            LlmService llmService,
            TwilioService twilioService,
            ConversationStore conversationStore,
            BookingFlowService bookingFlowService
    ) {
        this.sttService = sttService;
        this.llmService = llmService;
        this.twilioService = twilioService;
        this.conversationStore = conversationStore;
        this.bookingFlowService = bookingFlowService;
    }

    static class StreamState {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int silenceFrames = 0;
        boolean processing = false;
        boolean closed = false;
        String callSid;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        JsonNode root = mapper.readTree(message.getPayload());
        String event = root.path("event").asText();
        String streamSid = root.path("streamSid").asText();

        if ("start".equals(event)) {
            StreamState state = new StreamState();
            JsonNode start = root.path("start");
            if (!start.isMissingNode()) {
                state.callSid = start.path("callSid").asText();
            }
            if (state.callSid == null || state.callSid.isEmpty()) {
                state.callSid = root.path("callSid").asText();
            }
            streams.put(streamSid, state);
            log.info("Call started | streamSid={} callSid={}", streamSid, state.callSid);
            return;
        }

        if ("media".equals(event)) {
            handleMedia(streamSid, root, session);
            return;
        }

        if ("stop".equals(event)) {
            log.info("Call/stream ended | {}", streamSid);
            cleanup(streamSid);
        }
    }

    private void handleMedia(String streamSid, JsonNode root, WebSocketSession session) {

        StreamState state = streams.get(streamSid);
        if (state == null || state.closed || state.processing) return;

        String payload = root.path("media").path("payload").asText(null);
        if (StringUtils.isBlank(payload)) return;

        byte[] frame = Base64.getDecoder().decode(payload);
        state.buffer.write(frame, 0, frame.length);

        if (isSilent(frame)) {
            state.silenceFrames++;
        } else {
            state.silenceFrames = 0;
        }

        if (state.silenceFrames >= SILENCE_FRAMES && state.buffer.size() >= MIN_AUDIO_BYTES) {

            state.processing = true;

            byte[] utterance = state.buffer.toByteArray();
            state.buffer.reset();
            state.silenceFrames = 0;

            processUtteranceAsync(streamSid, utterance, state);
        }
    }

    private boolean isSilent(byte[] frame) {
        long sum = 0;
        for (byte b : frame) sum += Math.abs(b);
        return (sum / frame.length) < 4;
    }

    /**
     * Returns true if the exchange indicates the conversation is over (e.g. goodbye, thanks, have a great day).
     */
    private boolean isConversationEnded(String aiReply, String userMessage) {
        if (aiReply == null) return false;
        String ai = aiReply.toLowerCase().trim();
        String user = userMessage != null ? userMessage.toLowerCase().trim() : "";
        if (ai.contains("goodbye") || ai.contains("have a great day") || ai.contains("feel free to call")) {
            return true;
        }
        if (user.contains("goodbye") || user.contains("that's all") || user.contains("that is all")
                || user.contains("nothing else")
                || (user.contains("thank you") && (user.contains("bye") || user.length() < 30))) {
            return true;
        }
        return false;
    }

    private void processUtteranceAsync(String streamSid, byte[] audio, StreamState state) {
        CompletableFuture.runAsync(() -> {
            try {

                if (audio.length < MIN_AUDIO_BYTES || state.closed) return;

                String callSid = state.callSid;
                if (callSid == null || callSid.isEmpty()) {
                    log.warn("No callSid for stream {}; cannot speak response", streamSid);
                    return;
                }

                String userText = sttService.transcribe(audio);
                if (StringUtils.isBlank(userText) || userText.length() < 5) return;

                log.info("USER | {}", userText);
                conversationStore.appendUser(callSid, userText);

                List<ChatMessage> history = conversationStore.getHistory(callSid);
                BookingFlowService.BookingFlowResult flowResult = bookingFlowService.processUtterance(callSid, userText, history);

                String aiText;
                if (flowResult.bookingSuccess() && flowResult.bookedSlot() != null) {
                    AppointmentSlot s = flowResult.bookedSlot();
                    aiText = "Your appointment is confirmed for " + s.getSlotDate() + " at " + s.getStartTime() + " with " + s.getDoctor().getName() + ". Thank you for calling.";
                } else if (flowResult.cancelSuccess()) {
                    aiText = "Your appointment has been cancelled. Thank you for calling. Goodbye.";
                } else if (flowResult.rescheduleSuccess() && flowResult.bookedSlot() != null) {
                    AppointmentSlot s = flowResult.bookedSlot();
                    aiText = "Your appointment has been rescheduled to " + s.getSlotDate() + " at " + s.getStartTime() + " with " + s.getDoctor().getName() + ". Thank you for calling.";
                } else if (flowResult.bookingConflict()) {
                    aiText = flowResult.aiText() != null ? flowResult.aiText() : "Sorry, that slot was just booked. Let me offer you alternative times.";
                } else {
                    aiText = flowResult.aiText();
                }
                if (StringUtils.isBlank(aiText)) return;

                log.info("AI | {}", aiText);
                conversationStore.appendAssistant(callSid, aiText);

                boolean endCall = isConversationEnded(aiText, userText)
                        || flowResult.bookingSuccess() || flowResult.cancelSuccess() || flowResult.rescheduleSuccess();
                if (endCall) {
                    log.info("Conversation ended -> will hang up after speaking");
                }
                twilioService.speakResponse(callSid, aiText, endCall);

            } catch (Exception e) {
                log.error("Pipeline error", e);
            } finally {
                state.processing = false;
            }
        });
    }

    private void cleanup(String streamSid) {
        StreamState state = streams.remove(streamSid);
        if (state != null) {
            state.closed = true;
        }
    }
}
