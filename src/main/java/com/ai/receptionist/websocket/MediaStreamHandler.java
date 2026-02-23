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

    private static final int SILENCE_FRAMES = 12;
    private static final int MIN_AUDIO_BYTES = 8000;
    private static final int MAX_BUFFER_BYTES = 32000;

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
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        JsonNode root = mapper.readTree(message.getPayload());
        String event = root.path("event").asText();
        String streamSid = root.path("streamSid").asText();

        if ("start".equals(event)) {

            if (!activeStreams.add(streamSid)) return;

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
        if (state == null || state.closed) return;

        // ignore first 250ms (Twilio warmup noise)
        if (System.currentTimeMillis() - state.connectedAt < 250) return;

        String payload = root.path("media").path("payload").asText(null);
        if (payload == null) return;

        byte[] frame = Base64.getDecoder().decode(payload);
        state.buffer.write(frame, 0, frame.length);

        if (isSilent(frame)) state.silenceFrames++;
        else state.silenceFrames = 0;

        int size = state.buffer.size();

        boolean silenceTrigger =
                state.silenceFrames >= SILENCE_FRAMES && size >= MIN_AUDIO_BYTES;

        boolean overflowTrigger =
                size >= MAX_BUFFER_BYTES;

        if ((silenceTrigger || overflowTrigger) && !state.processing) {

            byte[] utterance = state.buffer.toByteArray();
            state.buffer.reset();
            state.silenceFrames = 0;
            state.processing = true;

            log.info("🎤 Processing speech chunk: {} bytes", utterance.length);

            processUtteranceAsync(streamSid, utterance, state);
        }
    }

    private boolean isSilent(byte[] frame) {
        long sum = 0;
        for (byte b : frame) sum += Math.abs(b);
        return (sum / frame.length) < 8;
    }

    private void processUtteranceAsync(String streamSid, byte[] audio, StreamState state) {

        CompletableFuture.runAsync(() -> {
            try {

                if (state.closed || audio.length < 4000) return;

                String callSid = state.callSid;
                String from = state.fromNumber == null ? "" : state.fromNumber;

                String userText = sttService.transcribe(audio);

                if (StringUtils.isBlank(userText)) {
                    handleSilence(state);
                    return;
                }

                String trimmed = userText.trim().toLowerCase();

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
                        aiText.toLowerCase().contains("appointment is confirmed")
                                || aiText.toLowerCase().contains("you're all set");

                twilioService.speakResponse(callSid, aiText, endCall);

                if (endCall) {
                    Thread.sleep(1200);
                    state.closed = true;
                }

            } catch (Exception e) {
                log.error("Pipeline error", e);
            } finally {
                state.processing = false;

                // ⭐ process buffered audio accumulated during STT
                if (state.buffer.size() >= MIN_AUDIO_BYTES) {
                    byte[] next = state.buffer.toByteArray();
                    state.buffer.reset();
                    processUtteranceAsync(streamSid, next, state);
                }
            }
        });
    }

    private void handleSilence(StreamState state) {
        state.unclearUtterances++;

        if (state.unclearUtterances <= 2) return;

        String callSid = state.callSid;
        String prompt = (state.unclearUtterances == 3)
                ? phrases.stillThere()
                : phrases.goodbye();

        boolean end = state.unclearUtterances > 3;

        conversationStore.appendAssistant(callSid, state.fromNumber, prompt);
        twilioService.speakResponse(callSid, prompt, end);
    }

    private void cleanup(String streamSid) {
        StreamState state = streams.remove(streamSid);
        activeStreams.remove(streamSid);
        if (state != null) state.closed = true;
    }
}