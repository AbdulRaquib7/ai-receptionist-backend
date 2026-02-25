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

    private static final int SILENCE_FRAMES = 18;
    private static final int MIN_AUDIO_BYTES = 12000;
    private static final int MAX_BUFFER_BYTES = 32000;

    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();
    /** Persists callSid -> fromNumber across multiple stream segments within the same call */
    private final Map<String, String> callFromNumbers = new ConcurrentHashMap<>();

    private final SttService sttService;
    private final LlmService llmService;
    private final TwilioService twilioService;
    private final ConversationStore conversationStore;
    private final BookingFlowService bookingFlowService;
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
            ResponsePhrases phrases) {

        this.sttService = sttService;
        this.llmService = llmService;
        this.twilioService = twilioService;
        this.conversationStore = conversationStore;
        this.bookingFlowService = bookingFlowService;
        this.phrases = phrases;
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
            // Only clean up the callSid mapping when no more streams exist for this call
            if (stopped != null && !callFromNumbers.isEmpty()) {
                boolean hasOtherStreams = streams.values().stream()
                        .anyMatch(s -> stopped.callSid.equals(s.callSid));
                if (!hasOtherStreams) {
                    callFromNumbers.remove(stopped.callSid);
                    bookingFlowService.clearPending(stopped.callSid);
                }
            }
        }
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

                List<ChatMessage> history = conversationStore.getHistory(callSid);
                List<String> summary = conversationStore.getConversationSummary(callSid);

                Optional<String> flowReply =
                        bookingFlowService.processUserMessage(
                                callSid,
                                fromNumber,
                                trimmed,
                                summary,
                                openAiApiKey,
                                openAiModel);

                String aiText = flowReply.orElseGet(() ->
                        llmService.generateReply(callSid, fromNumber, history));

                if (StringUtils.isBlank(aiText)) {
                    log.warn("⚠ Empty AI reply");
                    return;
                }

                log.info("🤖 AI REPLY: {}", aiText);
                conversationStore.appendAssistant(callSid, fromNumber, aiText);

                boolean endCall =
                        aiText.toLowerCase().contains("have a good day")
                                || aiText.toLowerCase().contains("bye.");

                twilioService.speakResponse(callSid, aiText, endCall);

            } catch (Exception e) {
                log.error("❌ PIPELINE ERROR", e);
            } finally {
                state.processing = false;
            }
        });
    }
}