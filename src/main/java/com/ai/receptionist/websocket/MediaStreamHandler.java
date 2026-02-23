package com.ai.receptionist.websocket;

import com.ai.receptionist.component.ConversationStore;
import com.ai.receptionist.component.ResponsePhrases;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.service.ConversationOrchestrator;
import com.ai.receptionist.service.SttService;
import com.ai.receptionist.service.TwilioService;
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
    private final Map<String, String> callSidToFrom = new ConcurrentHashMap<>();

    private final SttService sttService;
    private final TwilioService twilioService;
    private final ConversationStore conversationStore;
    private final ConversationOrchestrator orchestrator;
    private final ResponsePhrases phrases;

    @Value("${openai.api-key:}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String openAiModel;

    public MediaStreamHandler(
            SttService sttService,
            TwilioService twilioService,
            ConversationStore conversationStore,
            ConversationOrchestrator orchestrator,
            ResponsePhrases phrases) {

        this.sttService = sttService;
        this.twilioService = twilioService;
        this.conversationStore = conversationStore;
        this.orchestrator = orchestrator;
        this.phrases = phrases;
    }

    static class StreamState {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int silenceFrames = 0;
        boolean processing = false;
        boolean closed = false;
        String callSid;
        String fromNumber;
        long connectedAt;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        JsonNode root = mapper.readTree(message.getPayload());
        String event = root.path("event").asText();
        String streamSid = root.path("streamSid").asText();

//        log.info("📩 EVENT: {}", event);

        if ("start".equals(event)) {

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
            if (state.fromNumber == null)
                state.fromNumber = callSidToFrom.get(state.callSid);

            streams.put(streamSid, state);

            log.info("✅ STREAM CONNECTED | CallSid={}", state.callSid);
            return;
        }

        if ("media".equals(event)) {
            handleMedia(streamSid, root);
            return;
        }

        if ("stop".equals(event)) {
            log.warn("🛑 STREAM STOPPED");
            streams.remove(streamSid);
        }
    }

    private void handleMedia(String streamSid, JsonNode root) {

        StreamState state = streams.get(streamSid);
        if (state == null || state.closed) return;

        String payload = root.path("media").path("payload").asText(null);
        if (payload == null) return;

        byte[] frame = Base64.getDecoder().decode(payload);

        log.debug("🎧 frame bytes={}", frame.length);

        state.buffer.write(frame, 0, frame.length);

        boolean silent = isSilent(frame);

        if (silent) state.silenceFrames++;
        else state.silenceFrames = 0;

        int size = state.buffer.size();

        log.debug("buffer={} silenceFrames={}", size, state.silenceFrames);

        boolean silenceTrigger =
                state.silenceFrames >= SILENCE_FRAMES && size >= MIN_AUDIO_BYTES;

        boolean overflowTrigger = size >= MAX_BUFFER_BYTES;

        if ((silenceTrigger || overflowTrigger) && !state.processing) {

            byte[] utterance = state.buffer.toByteArray();
            state.buffer.reset();
            state.silenceFrames = 0;
            state.processing = true;

            log.info("🎤 SPEECH DETECTED → {} bytes", utterance.length);

            processUtteranceAsync(utterance, state);
        }
    }

    private boolean isSilent(byte[] frame) {
        long sum = 0;
        for (byte b : frame) sum += Math.abs(b);
        return (sum / frame.length) < 8;
    }

    private void processUtteranceAsync(byte[] audio, StreamState state) {

        CompletableFuture.runAsync(() -> {
            try {

                if (audio.length < 4000) {
                    log.warn("⚠ Ignored small audio");
                    return;
                }

                log.info("🧠 Sending to STT…");

                String text = sttService.transcribe(audio);

                log.info("🧠 STT RESULT = [{}]", text);

                if (StringUtils.isBlank(text)) {
                    log.warn("⚠ Empty transcription");
                    return;
                }

                String fromNumber = state.fromNumber != null ? state.fromNumber : "";
                String trimmed = text.trim();
                conversationStore.appendUser(state.callSid, fromNumber, trimmed);

                List<String> summary = conversationStore.getConversationSummary(state.callSid);
                List<ChatMessage> history = conversationStore.getHistory(state.callSid);

                ConversationOrchestrator.OrchestratorResult result =
                        orchestrator.process(state.callSid, fromNumber, trimmed,
                                summary, history, openAiApiKey, openAiModel);

                String reply = result.getTextToSpeak();

                log.info("🤖 AI RESPONSE: {}", reply);

                if (StringUtils.isNotBlank(reply))
                    conversationStore.appendAssistant(state.callSid, fromNumber, reply);

                twilioService.speakResponse(state.callSid, reply, result.isEndCall());

            } catch (Exception e) {
                log.error("Pipeline error", e);
            } finally {
                state.processing = false;
            }
        });
    }
}