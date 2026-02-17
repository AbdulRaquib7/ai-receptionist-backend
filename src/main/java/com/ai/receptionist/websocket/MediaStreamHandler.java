package com.ai.receptionist.websocket;

import com.ai.receptionist.component.ConversationStore;
import com.ai.receptionist.component.ResponsePhrases;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.service.BookingFlowService;
import com.ai.receptionist.service.YesNoClassifierService;
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
        /** Counts consecutive unclear/silent utterances to drive gentle timeout prompts. */
        int unclearUtterances = 0;
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

    /**
     * Handle cases where STT could not confidently extract user speech
     * (pure silence, very short noise, or nonsensical content).
     * We do NOT treat this as the user ending the call; instead we gently
     * prompt them, then only after multiple missed turns we hang up.
     */
    private void handleUnclearUtterance(StreamState state) {
        if (state == null || state.closed) {
            return;
        }
        state.unclearUtterances++;
        String callSid = state.callSid;
        if (callSid == null || callSid.isEmpty()) {
            return;
        }

        String prompt;
        boolean endCall = false;

        // 1st time: simple "I didn't catch that" clarification
        if (state.unclearUtterances == 1) {
            prompt = phrases != null
                    ? phrases.couldYouRepeat()
                    : "Sorry, I didn't catch that. Could you repeat?";
        }
        // 2nd time: reassure we're still on the line
        else if (state.unclearUtterances == 2) {
            prompt = phrases != null
                    ? phrases.stillHere()
                    : "Hello? I'm here. Please go ahead.";
        }
        // 3rd time: explicit "are you still there?"
        else if (state.unclearUtterances == 3) {
            prompt = phrases != null
                    ? phrases.stillThere()
                    : "Are you still there?";
        }
        // 4th+ time: assume user has left, politely end
        else {
            prompt = phrases != null
                    ? phrases.goodbye()
                    : "Alright, have a good day! Bye.";
            endCall = true;
        }

        log.info("AI | {}", prompt);
        conversationStore.appendAssistant(callSid, state.fromNumber != null ? state.fromNumber : "", prompt);
        twilioService.speakResponse(callSid, prompt, endCall);
        if (endCall) {
            state.closed = true;
        }
    }

    private boolean isConversationEnded(String aiReply, String userMessage, boolean hasPending) {
        if (aiReply == null) return false;
        String ai = aiReply.toLowerCase().trim();
        String user = userMessage != null ? userMessage.toLowerCase().trim() : "";

        if (ai.contains("anything else i can help") || ai.contains("anything else") && ai.contains("help")
                || ai.contains("can i help you with") || ai.contains("help you with anything")
                || ai.contains("what can i help") || ai.contains("would you like me to confirm")) {
            return false;
        }

        if ((user.equals("no") || user.equals("nope") || user.equals("nah")) && !hasPending) {
            if (ai.contains("good day") || ai.contains("goodbye") || ai.contains("take care") || ai.contains("bye")) {
                return true;
            }
        }

        if (user.contains("goodbye") || user.contains("that's all") || user.contains("nothing else")
                || (user.contains("thank you") && (user.contains("bye") || user.contains("that's all")))
                || (user.contains("bye") && user.length() <= 12)) {
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
                if (StringUtils.isBlank(userText)) {
                    handleUnclearUtterance(state);
                    return;
                }
                String trimmed = userText.trim();
                if (trimmed.length() < 2) {
                    handleUnclearUtterance(state);
                    return;
                }
                if (trimmed.length() < 5 && !yesNoClassifier.isShortAffirmativeOrNegative(trimmed)) {
                    handleUnclearUtterance(state);
                    return;
                }

                // We have a proper utterance; reset unclear counter.
                state.unclearUtterances = 0;

                String fromNumber = state.fromNumber != null ? state.fromNumber : "";
                log.info("USER | {}", trimmed);
                conversationStore.appendUser(callSid, fromNumber, trimmed);
                List<ChatMessage> history = conversationStore.getHistory(callSid);
                List<String> summary = history.stream()
                        .map(m -> m.getRole() + ": " + m.getContent())
                        .collect(java.util.stream.Collectors.toList());

                Optional<String> flowReply = bookingFlowService.processUserMessage(
                        callSid, fromNumber, trimmed, summary, openAiApiKey, openAiModel);
                String aiText = flowReply.orElseGet(() -> llmService.generateReply(callSid, fromNumber, history));
                if (StringUtils.isBlank(aiText)) return;

                log.info("AI | {}", aiText);
                conversationStore.appendAssistant(callSid, fromNumber, aiText);

                boolean hasPending = bookingFlowService.getPending(callSid) != null
                        && bookingFlowService.getPending(callSid).hasAnyPending();
                boolean endCall = isConversationEnded(aiText, trimmed, hasPending);
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
