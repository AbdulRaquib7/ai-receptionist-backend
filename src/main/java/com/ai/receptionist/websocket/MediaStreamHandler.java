package com.ai.receptionist.websocket;

import com.ai.receptionist.component.ConversationStore;
import com.ai.receptionist.component.ResponsePhrases;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.service.*;
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

    private static final int SILENCE_FRAMES = 20;
    private static final int MIN_AUDIO_BYTES = 16000;

    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();
    private final Set<String> activeStreams = ConcurrentHashMap.newKeySet();
    private final Map<String, String> callSidToFrom = new ConcurrentHashMap<>();

    private final SttService sttService;
    private final LlmService llmService;
    private final TwilioService twilioService;
    private final ConversationStore conversationStore;
    private final BookingFlowService bookingFlowService;
    private final YesNoClassifierService yesNoClassifier;
    private final ResponsePhrases phrases;

    @Value("${openai.api-key:}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String openAiModel;

    public MediaStreamHandler(
            SttService sttService,
            LlmService llmService,
            TwilioService twilioService,
            ConversationStore conversationStore,
            BookingFlowService bookingFlowService,
            YesNoClassifierService yesNoClassifier,
            ResponsePhrases phrases
    ) {
        this.sttService = sttService;
        this.llmService = llmService;
        this.twilioService = twilioService;
        this.conversationStore = conversationStore;
        this.bookingFlowService = bookingFlowService;
        this.yesNoClassifier = yesNoClassifier;
        this.phrases = phrases;
    }

    static class StreamState {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int silenceFrames = 0;
        boolean processing = false;
        boolean closed = false;
        int unclearUtterances = 0;
        String callSid;
        String fromNumber;

        long connectedAt;
        boolean justReconnected = true;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        JsonNode root = mapper.readTree(message.getPayload());
        String event = root.path("event").asText();
        String streamSid = root.path("streamSid").asText();

        if ("start".equals(event)) {

            if (!activeStreams.add(streamSid)) {
                log.debug("Duplicate stream ignored: {}", streamSid);
                return;
            }

            StreamState state = new StreamState();
            state.connectedAt = System.currentTimeMillis();

            JsonNode start = root.path("start");
            state.callSid = start.path("callSid").asText("");

            JsonNode custom = start.path("customParameters");
            if (!custom.isMissingNode()) {
                String from = custom.path("From").asText("");
                if (!from.isEmpty()) {
                    state.fromNumber = from;
                    callSidToFrom.put(state.callSid, from);
                }
            }

            if (state.fromNumber == null) {
                state.fromNumber = callSidToFrom.get(state.callSid);
            }

            streams.put(streamSid, state);
            log.info("Call started | streamSid={} callSid={}", streamSid, state.callSid);
            return;
        }

        if ("media".equals(event)) {
            handleMedia(streamSid, root);
            return;
        }

        if ("stop".equals(event)) {
            cleanup(streamSid);
        }
    }

    private void handleMedia(String streamSid, JsonNode root) {

        StreamState state = streams.get(streamSid);
        if (state == null || state.closed || state.processing) return;

        if (state.justReconnected &&
                System.currentTimeMillis() - state.connectedAt < 400) {
            return;
        }
        state.justReconnected = false;

        String payload = root.path("media").path("payload").asText(null);
        if (payload == null) return;

        byte[] frame = Base64.getDecoder().decode(payload);
        state.buffer.write(frame, 0, frame.length);

        if (isSilent(frame)) state.silenceFrames++;
        else state.silenceFrames = 0;

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
        return (sum / frame.length) < 6;
    }

    private void processUtteranceAsync(String streamSid, byte[] audio, StreamState state) {

        CompletableFuture.runAsync(() -> {
            try {

                if (audio.length < MIN_AUDIO_BYTES || state.closed) return;

                String callSid = state.callSid;
                String from = state.fromNumber == null ? "" : state.fromNumber;

                String userText = sttService.transcribe(audio);

                if (StringUtils.isBlank(userText)) {
                    handleSilence(state);
                    return;
                }

                String trimmed = userText.trim().toLowerCase();

                // 🔥 ignore noise but allow confirmations
                if (isNoiseWord(trimmed) && !isCriticalConfirmation(trimmed)) {
                    log.info("Ignoring probable noise word: {}", trimmed);
                    return;
                }

                state.unclearUtterances = 0;

                log.info("USER | {}", trimmed);
                conversationStore.appendUser(callSid, from, trimmed);

                List<ChatMessage> history = conversationStore.getHistory(callSid);
                List<String> summary = conversationStore.getConversationSummary(callSid);

                Optional<String> flowReply =
                        bookingFlowService.processUserMessage(
                                callSid, from, trimmed, summary, openAiApiKey, openAiModel);

                String aiText = flowReply.orElseGet(() ->
                        llmService.generateReply(callSid, from, history));

                if (StringUtils.isBlank(aiText)) return;

                log.info("AI | {}", aiText);
                conversationStore.appendAssistant(callSid, from, aiText);

                boolean endCall =
                        isGoodbye(trimmed, aiText)
                                || aiText.toLowerCase().contains("appointment is confirmed")
                                || aiText.toLowerCase().contains("you're all set");

                twilioService.speakResponse(callSid, aiText, endCall);

                if (endCall) {
                    Thread.sleep(1200); // allow audio playback
                    state.closed = true;
                }

            } catch (Exception e) {
                log.error("Pipeline error", e);
            } finally {
                state.processing = false;
            }
        });
    }

    private boolean isNoiseWord(String text) {
        if (text == null) return true;

        String cleaned = text.replaceAll("[^a-zA-Z]", "");

        if (cleaned.isBlank()) return true;

        if (cleaned.length() <= 2) return true;

        if (isCriticalConfirmation(cleaned)) return false;

        return cleaned.length() <= 3;
    }
    
    private boolean isCriticalConfirmation(String text) {
        if (text == null) return false;

        String t = text
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z]", ""); // remove punctuation

        return t.equals("yes")
                || t.equals("yeah")
                || t.equals("yep")
                || t.equals("confirm")
                || t.equals("correct")
                || t.equals("right")
                || t.equals("no")
                || t.equals("nope")
                || t.equals("cancel");
    }

    private void handleSilence(StreamState state) {
        state.unclearUtterances++;

        if (state.unclearUtterances <= 2) return;

        String callSid = state.callSid;
        String prompt;
        boolean end = false;

        if (state.unclearUtterances == 3) {
            prompt = phrases.stillThere();
        } else {
            prompt = phrases.goodbye();
            end = true;
        }

        conversationStore.appendAssistant(callSid, state.fromNumber, prompt);
        twilioService.speakResponse(callSid, prompt, end);
    }

    private boolean isGoodbye(String user, String ai) {
        String u = user.toLowerCase();
        String a = ai.toLowerCase();

        if (u.equals("bye") || u.equals("goodbye") || u.equals("thanks bye"))
            return true;

        return a.contains("take care")
                || a.contains("have a great day")
                || a.contains("goodbye");
    }

    private void cleanup(String streamSid) {
        StreamState state = streams.remove(streamSid);
        activeStreams.remove(streamSid);
        if (state != null) state.closed = true;
    }
    
    
}
