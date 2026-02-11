package com.ai.receptionist.websocket;

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

import org.springframework.beans.factory.annotation.Value;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MediaStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MediaStreamHandler.class);

    private static final int SILENCE_FRAMES = 20;
    private static final int MIN_AUDIO_BYTES = 12000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();
    /** Persists From number across stream reconnects (continue-call does not pass custom params) */
    private final Map<String, String> callSidToFrom = new ConcurrentHashMap<>();

    private final SttService sttService;
    private final LlmService llmService;
    private final TwilioService twilioService;
    private final ConversationStore conversationStore;
    private final BookingFlowService bookingFlowService;

    @Value("${openai.api-key:}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String openAiModel;

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
        String fromNumber;
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
                state.callSid = start.path("callSid").asText("");
                JsonNode custom = start.path("customParameters");
                if (!custom.isMissingNode()) {
                    String from = custom.path("From").asText("");
                    if (!from.isEmpty()) {
                        state.fromNumber = from;
                        callSidToFrom.put(state.callSid, from);
                    }
                }
            }
            if (state.callSid == null || state.callSid.isEmpty()) {
                state.callSid = root.path("callSid").asText("");
            }
            if (state.fromNumber == null) {
                state.fromNumber = callSidToFrom.get(state.callSid);
            }
            streams.put(streamSid, state);
            log.info("Call started | streamSid={} callSid={} from={}", streamSid, state.callSid, state.fromNumber);
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

    private static boolean isShortAffirmativeOrNegative(String s) {
        if (s == null || s.length() > 12) return false;
        String t = s.trim().toLowerCase();
        return t.matches("yes|yeah|yep|ya|ok|okay|no|nope|sure|confirm|cancel");
    }

    /**
     * Returns true if the exchange indicates the conversation is over.
     */
    private boolean isConversationEnded(String aiReply, String userMessage) {
        if (aiReply == null) return false;
        String ai = aiReply.toLowerCase().trim();
        String user = userMessage != null ? userMessage.toLowerCase().trim() : "";

        // AI said explicit closing
        if (ai.contains("goodbye") || ai.contains("take care") || ai.contains("have a great day")
                || ai.contains("has been cancelled") || ai.contains("has been rescheduled")
                || ai.contains("appointment is confirmed")) {
            return true;
        }

        // User-triggered: require longer phrase to avoid noise ("bye", "thanks" alone often misheard)
        if (user.length() < 8) return false;
        if (user.contains("goodbye") || user.contains("that's all") || user.contains("nothing else")) {
            return true;
        }
        if (user.contains("thank you") && (user.contains("goodbye") || user.contains("bye bye") || user.contains("that's all"))) {
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
                if (StringUtils.isBlank(userText)) return;
                if (userText.length() < 2) return;
                if (userText.length() < 5 && !isShortAffirmativeOrNegative(userText)) return;

                String fromNumber = state.fromNumber != null ? state.fromNumber : "";
                log.info("USER | {}", userText);
                conversationStore.appendUser(callSid, fromNumber, userText);
                List<ChatMessage> history = conversationStore.getHistory(callSid);
                List<String> summary = history.stream()
                        .map(m -> m.getRole() + ": " + m.getContent())
                        .collect(java.util.stream.Collectors.toList());

                Optional<String> flowReply = bookingFlowService.processUserMessage(
                        callSid, fromNumber, userText, summary, openAiApiKey, openAiModel);
                String aiText = flowReply.orElseGet(() -> llmService.generateReply(callSid, fromNumber, history));
                if (StringUtils.isBlank(aiText)) return;

                log.info("AI | {}", aiText);
                conversationStore.appendAssistant(callSid, fromNumber, aiText);

                boolean endCall = isConversationEnded(aiText, userText);
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
