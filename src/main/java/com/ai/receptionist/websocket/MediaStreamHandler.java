package com.ai.receptionist.websocket;

import com.ai.receptionist.component.ConversationStore;
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
            YesNoClassifierService yesNoClassifier
    ) {
        this.sttService = sttService;
        this.llmService = llmService;
        this.twilioService = twilioService;
        this.conversationStore = conversationStore;
        this.bookingFlowService = bookingFlowService;
        this.yesNoClassifier = yesNoClassifier;
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

    /**
     * Returns true only when conversation is truly finished. Do NOT end when AI asks a question.
     * Safe rules: end only when user explicitly declines more help or says goodbye.
     */
    private boolean isConversationEnded(String aiReply, String userMessage, boolean hasPending) {
        if (aiReply == null) return false;
        String ai = aiReply.toLowerCase().trim();
        String user = userMessage != null ? userMessage.toLowerCase().trim() : "";

        // NEVER end when AI ends with a question - wait for user response
        if (ai.contains("anything else i can help") || ai.contains("anything else") && ai.contains("help")
                || ai.contains("can i help you with") || ai.contains("help you with anything")
                || ai.contains("what can i help") || ai.contains("would you like me to confirm")) {
            return false;
        }

        // User declined more help: short "no" when AI is about to say goodbye
        if ((user.equals("no") || user.equals("nope") || user.equals("nah")) && !hasPending) {
            if (ai.contains("good day") || ai.contains("goodbye") || ai.contains("take care") || ai.contains("bye")) {
                return true;
            }
        }

        // User said goodbye / end call
        if (user.contains("goodbye") || user.contains("that's all") || user.contains("nothing else")
                || (user.contains("thank you") && (user.contains("bye") || user.contains("that's all")))) {
            return true;
        }

        // AI said explicit final goodbye (after user declined)
        if (ai.contains("have a good day") || ai.contains("have a great day") || ai.contains("take care")
                || ai.contains("feel free to call") || ai.contains("thank you for calling")) {
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
                if (userText.length() < 5 && !yesNoClassifier.isShortAffirmativeOrNegative(userText)) return;

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

                boolean hasPending = bookingFlowService.getPending(callSid) != null
                        && bookingFlowService.getPending(callSid).hasAnyPending();
                boolean endCall = isConversationEnded(aiText, userText, hasPending);
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
