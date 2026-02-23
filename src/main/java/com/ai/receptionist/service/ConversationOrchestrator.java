package com.ai.receptionist.service;

import com.ai.receptionist.dto.FlowResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Single entry for conversation flow: routes user input, keeps flow state,
 * never lets LLM control booking decisions. Uses BookingFlowService for
 * flow outcome and LlmService only for open-ended replies.
 */
@Service
public class ConversationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ConversationOrchestrator.class);

    private final BookingFlowService bookingFlowService;
    private final VoiceResponseService voiceResponseService;
    private final LlmService llmService;

    public ConversationOrchestrator(BookingFlowService bookingFlowService,
                                    VoiceResponseService voiceResponseService,
                                    LlmService llmService) {
        this.bookingFlowService = bookingFlowService;
        this.voiceResponseService = voiceResponseService;
        this.llmService = llmService;
    }

    /**
     * Process user message and return text to speak and whether to end the call.
     */
    public OrchestratorResult process(String callSid, String fromNumber, String userText,
                                      List<String> conversationSummary,
                                      List<com.ai.receptionist.entity.ChatMessage> history,
                                      String openAiKey, String openAiModel) {
        Optional<FlowResponse> flow = bookingFlowService.processUserMessage(
                callSid, fromNumber, userText, conversationSummary, openAiKey, openAiModel);

        if (flow.isPresent()) {
            FlowResponse response = flow.get();
            if (response.getType() == FlowResponse.Type.NONE) {
                String llmText = llmService.generateReply(callSid, fromNumber, history);
                logFlow(callSid, "NONE", "llm");
                return new OrchestratorResult(
                        llmText != null && !llmText.isBlank() ? llmText : "",
                        false);
            }
            String speech = voiceResponseService.toSpeech(response);
            boolean endCall = response.isEndCall();
            logFlow(callSid, response.getType().name(), "flow");
            return new OrchestratorResult(speech, endCall);
        }

        String llmText = llmService.generateReply(callSid, fromNumber, history);
        logFlow(callSid, "empty", "llm");
        return new OrchestratorResult(
                llmText != null && !llmText.isBlank() ? llmText : "",
                false);
    }

    private void logFlow(String callSid, String outcome, String source) {
        log.debug("[{}] flow outcome={} source={}", callSid, outcome, source);
    }

    public static final class OrchestratorResult {
        private final String textToSpeak;
        private final boolean endCall;

        public OrchestratorResult(String textToSpeak, boolean endCall) {
            this.textToSpeak = textToSpeak != null ? textToSpeak : "";
            this.endCall = endCall;
        }

        public String getTextToSpeak() {
            return textToSpeak;
        }

        public boolean isEndCall() {
            return endCall;
        }
    }
}
