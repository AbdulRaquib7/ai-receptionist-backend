package com.ai.receptionist.websocket;

import com.ai.receptionist.entity.ChatMessage;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MediaStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MediaStreamHandler.class);

    private static final int SILENCE_FRAMES = 20;
    private static final int MIN_AUDIO_BYTES = 12_000;
    private static final int MAX_AUDIO_BYTES = 160_000; 

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();

    private final SttService sttService;
    private final LlmService llmService;
    private final TwilioService twilioService;
    private final ConversationStore conversationStore;
    private final Executor sttExecutor;

    public MediaStreamHandler(
            SttService sttService,
            LlmService llmService,
            TwilioService twilioService,
            ConversationStore conversationStore
    ) {
        this.sttService = sttService;
        this.llmService = llmService;
        this.twilioService = twilioService;
        this.conversationStore = conversationStore;

        this.sttExecutor = Executors.newFixedThreadPool(4);
    }

    static class StreamState {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        AtomicBoolean processing = new AtomicBoolean(false);
        int silenceFrames = 0;
        boolean closed = false;
        String callSid;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        JsonNode root = mapper.readTree(message.getPayload());
        String event = root.path("event").asText();
        String streamSid = root.path("streamSid").asText();

        switch (event) {

        case "start":
            StreamState state = new StreamState();
            JsonNode start = root.path("start");
            state.callSid = start.path("callSid").asText();
            streams.put(streamSid, state);
            log.info("Call started | streamSid={} callSid={}", streamSid, state.callSid);
            break;

        case "media":
            handleMedia(streamSid, root);
            break;

        case "stop":
            log.info("Call/stream ended | {}", streamSid);
            cleanup(streamSid);
            break;

        default:
            break;
    }

    }

    private void handleMedia(String streamSid, JsonNode root) {

        StreamState state = streams.get(streamSid);
        if (state == null || state.closed) return;

        String payload = root.path("media").path("payload").asText(null);
        if (StringUtils.isBlank(payload)) return;

        byte[] frame = Base64.getDecoder().decode(payload);
        state.buffer.write(frame, 0, frame.length);

        if (isSilent(frame)) {
            state.silenceFrames++;
        } else {
            state.silenceFrames = 0;
        }

        if (state.silenceFrames >= SILENCE_FRAMES &&
            state.buffer.size() >= MIN_AUDIO_BYTES &&
            state.processing.compareAndSet(false, true)) {

            byte[] utterance = state.buffer.toByteArray();
            state.buffer.reset();
            state.silenceFrames = 0;

            if (utterance.length > MAX_AUDIO_BYTES) {
                utterance = java.util.Arrays.copyOf(utterance, MAX_AUDIO_BYTES);
            }

            processUtteranceAsync(streamSid, utterance, state);
        }
    }

    private boolean isSilent(byte[] frame) {
        long sum = 0;
        for (byte b : frame) sum += Math.abs(b);
        return (sum / frame.length) < 4;
    }

    private void processUtteranceAsync(String streamSid, byte[] audio, StreamState state) {

        CompletableFuture.runAsync(() -> {
            try {
                if (state.closed || audio.length < MIN_AUDIO_BYTES) return;

                String callSid = state.callSid;
                if (StringUtils.isBlank(callSid)) return;

                String userText = sttService.transcribe(audio);
                if (StringUtils.isBlank(userText)) {
                    twilioService.speakResponse(
                            callSid,
                            "Sorry, I didn’t catch that. Could you please repeat?",
                            false
                    );
                    return;
                }

                log.info("USER | {}", userText);
                conversationStore.appendUser(callSid, userText);

                List<ChatMessage> history = conversationStore.getHistory(callSid);
                String aiText = llmService.generateReply(history);
                if (StringUtils.isBlank(aiText)) return;

                log.info("AI | {}", aiText);
                conversationStore.appendAssistant(callSid, aiText);

                boolean endCall = isConversationEnded(aiText, userText);
                twilioService.speakResponse(callSid, aiText, endCall);

            } catch (Exception e) {
                log.error("Pipeline error", e);
            } finally {
                state.processing.set(false);
            }
        }, sttExecutor);
    }

    private boolean isConversationEnded(String aiReply, String userMessage) {
        if (aiReply == null) return false;
        String ai = aiReply.toLowerCase();
        String user = userMessage != null ? userMessage.toLowerCase() : "";

        return ai.contains("goodbye")
                || ai.contains("have a great day")
                || user.contains("that's all")
                || user.contains("nothing else")
                || (user.contains("thank you") && user.length() < 30);
    }

    private void cleanup(String streamSid) {
        StreamState state = streams.remove(streamSid);
        if (state != null) {
            state.closed = true;
        }
    }
}
