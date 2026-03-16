package com.ai.receptionist.service;

import com.ai.receptionist.component.ConversationStore;
import com.ai.receptionist.dto.PendingActionDto;
import com.ai.receptionist.entity.CallSession;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.exception.LlmException;
import com.ai.receptionist.exception.SttException;
import com.ai.receptionist.utils.LogSanitizer;
import com.ai.receptionist.utils.YesNoResult;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Orchestrates the full conversation pipeline for a single user utterance:
 * STT → context lookup → confirmation check → LLM reply → TTS → farewell detection.
 *
 * Extracted from MediaStreamHandler so that the WebSocket handler only deals with
 * audio buffering and silence detection, while this service handles business logic.
 */
@Service
@RequiredArgsConstructor
public class ConversationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ConversationOrchestrator.class);

    private final SttService sttService;
    private final LlmFlowService llmFlowService;
    private final PendingActionService pendingActionService;
    private final ConfirmationExecutionService confirmationExecutionService;
    private final YesNoClassifierService yesNoClassifier;
    private final ConversationStore conversationStore;
    private final TwilioService twilioService;
    private final TenantService tenantService;
    private final CallSessionService callSessionService;

    /**
     * Callback to allow the caller (MediaStreamHandler) to clean up state
     * when the call ends (farewell detected).
     */
    @FunctionalInterface
    public interface EndCallCallback {
        void onEndCall(String callSid);
    }

    /**
     * Processes a single audio utterance end-to-end.
     *
     * @param callSid         Twilio call SID
     * @param fromNumber      Caller phone number
     * @param tenantId        Tenant ID (resolved from inbound call)
     * @param audio           Raw mu-law audio bytes from Twilio
     * @param endCallCallback Called when the AI reply signals a farewell (call should hang up)
     */
    public void processUtterance(String callSid, String fromNumber, Long tenantId, byte[] audio, EndCallCallback endCallCallback) {

        // --- STT ---
        String userText;
        try {
            log.info("➡ Sending to STT | bytes={}", audio.length);
            userText = sttService.transcribe(audio);
        } catch (SttException e) {
            log.error("STT failed for call {}: {}", callSid, e.getMessage());
            twilioService.speakResponse(callSid,
                    "I'm having trouble hearing you. Could you say that again?", false, tenantId);
            return;
        }

        if (StringUtils.isBlank(userText)) {
            log.warn("⚠ STT returned empty text");
            return;
        }

        // Truncate excessively long transcriptions to prevent prompt injection / cost spikes
        if (userText.length() > 2000) {
            log.warn("User text truncated from {} to 2000 chars for call {}", userText.length(), callSid);
            userText = userText.substring(0, 2000);
        }

        log.debug("🧑 USER SAID: {}", userText);
        log.info("🧑 USER SAID: [{}]", LogSanitizer.truncateText(userText));

        // Mark call as in-progress on first utterance and tag legacy pipeline
        callSessionService.markInProgress(callSid);
        callSessionService.setPipelineTag(callSid, "LEGACY");

        String trimmed = userText.trim();
        conversationStore.appendUser(callSid, fromNumber, trimmed);

        // Resume: history hydrates from conversation_history when in-memory is empty (e.g. stream reconnect)
        List<ChatMessage> history = conversationStore.getHistory(callSid);

        // --- Check for hangup request ---
        if (isHangupRequest(trimmed)) {
            Optional<PendingActionDto> pendingOpt = pendingActionService.getIfAwaitingConfirmation(callSid);
            
            if (pendingOpt.isPresent()) {
                // During pending action: ask to confirm abort
                String abortMsg = "Are you sure you want to cancel the " + 
                        mapIntentToAction(pendingOpt.get().getIntent()) + 
                        " process and end the call?";
                log.info("Hangup during pending action for call {}", callSid);
                conversationStore.appendAssistant(callSid, fromNumber, abortMsg);
                twilioService.speakResponse(callSid, abortMsg, false, tenantId);
                return;
            } else {
                // No pending: just hangup
                String goodbye = "Thank you for calling. Goodbye!";
                log.info("Hangup request for call {}, no pending action", callSid);
                conversationStore.appendAssistant(callSid, fromNumber, goodbye);
                twilioService.speakResponse(callSid, goodbye, true, tenantId);
                callSessionService.completeCall(callSid);
                generateCallSummaryAsync(callSid, tenantId);
                if (endCallCallback != null) {
                    endCallCallback.onEndCall(callSid);
                }
                return;
            }
        }

        // Atomic check-and-get: eliminates TOCTOU race between check and use
        Optional<PendingActionDto> pendingOpt = pendingActionService.getIfAwaitingConfirmation(callSid);
        if (pendingOpt.isPresent()) {
            log.info("Pending action currently stored for call {}: intent={} awaitingConfirmation=true",
                    callSid,
                    pendingOpt.get().getIntent());
        }
        YesNoResult yesNo = yesNoClassifier.classify(trimmed);

        String aiText = null;
        PendingActionDto actionFromLlm = null;

        // Pending confirmation + user said yes → execute action (backend writes to DB only here)
        if (pendingOpt.isPresent() && yesNo == YesNoResult.YES) {
            PendingActionDto pending = pendingOpt.get();
            log.info("User confirmed pending action with YES for call {}", callSid);
            Optional<String> executed = confirmationExecutionService.execute(callSid, fromNumber, tenantId, pending);
            if (executed.isPresent()) {
                aiText = executed.get();
                pendingActionService.clearPending(callSid);
                // Track outcome based on executed action
                CallSession.Outcome outcome = mapIntentToOutcome(pending.getIntent());
                callSessionService.setOutcome(callSid, outcome);
                log.info("Pending action executed and cleared for call {}", callSid);
            }
        }

        // Pending + user said no → check if abort confirmation (user said no to "Are you sure...")
        if (aiText == null && pendingOpt.isPresent() && yesNo == YesNoResult.NO) {
            if (!history.isEmpty() && "assistant".equals(history.get(history.size() - 1).getRole())) {
                String lastMsg = history.get(history.size() - 1).getContent().toLowerCase();
                if (lastMsg.contains("cancel") && lastMsg.contains("process") && lastMsg.contains("end call")) {
                    // User said NO to abort - continue with pending action
                    String cont = "Let's continue then. Should I go ahead and " + 
                            mapIntentToAction(pendingOpt.get().getIntent()) + "?";
                    aiText = cont;
                    log.info("User declined to abort for call {}", callSid);
                } else {
                    pendingActionService.clearPending(callSid);
                    aiText = "No problem. What would you like to do?";
                }
            } else {
                pendingActionService.clearPending(callSid);
                aiText = "No problem. What would you like to do?";
            }
        }
            aiText = "No problem. What would you like to do?";
        }

        // --- LLM ---
        if (aiText == null) {
            try {
                LlmFlowService.FlowResponse response = llmFlowService.generateReply(callSid, fromNumber, tenantId, history);
                aiText = response.getMessage();
                actionFromLlm = response.getAction();
            } catch (LlmException e) {
                log.error("LLM failed for call {}: {}", callSid, e.getMessage());
                twilioService.speakResponse(callSid,
                        "I'm having a quick technical moment. Could you repeat that?", false, tenantId);
                return;
            }
        }

        // If LLM produced an action but there was no prior pending action,
        // and the user utterance was effectively a YES (e.g. \"Yes, yes\" or
        // confirmation phrasing), treat this as immediate confirmation and
        // execute the action instead of requiring an extra turn.
        if (actionFromLlm != null) {
            if (!pendingOpt.isPresent() && yesNo == YesNoResult.YES) {
                log.info("Executing newly suggested action immediately for call {} based on YES utterance", callSid);
                Optional<String> executed = confirmationExecutionService.execute(callSid, fromNumber, tenantId, actionFromLlm);
                if (executed.isPresent()) {
                    aiText = executed.get();
                    pendingActionService.clearPending(callSid);
                    CallSession.Outcome outcome = mapIntentToOutcome(actionFromLlm.getIntent());
                    callSessionService.setOutcome(callSid, outcome);
                    log.info("Newly suggested action executed for call {} with outcome={}", callSid, outcome);
                    actionFromLlm = null; // already executed; don't store as pending
                } else {
                    // Execution failed (e.g. slot not available); fall back to storing as pending so flow can continue safely.
                    pendingActionService.setPending(callSid, actionFromLlm);
                }
            } else {
                // Normal case: LLM is asking user to CONFIRM; store as pending.
                pendingActionService.setPending(callSid, actionFromLlm);
            }
        }

        if (StringUtils.isBlank(aiText)) {
            log.warn("⚠ Empty AI reply");
            return;
        }

        log.debug("🤖 AI REPLY: {}", aiText);
        log.info("🤖 AI REPLY: [{}]", LogSanitizer.truncateText(aiText));
        conversationStore.appendAssistant(callSid, fromNumber, aiText);

        boolean endCall = isEndCallReply(aiText, tenantId);

        twilioService.speakResponse(callSid, aiText, endCall, tenantId);
        if (endCall) {
            pendingActionService.clearPending(callSid);
            callSessionService.completeCall(callSid);
            generateCallSummaryAsync(callSid, tenantId);
            if (endCallCallback != null) {
                endCallCallback.onEndCall(callSid);
            }
        }
    }

    /**
     * Determines if the AI reply signals the end of the call.
     * Uses tenant-configurable farewell phrases loaded from TenantService.
     */
    private boolean isEndCallReply(String text, Long tenantId) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        List<String> phrases = tenantService.getFarewellPhrases(tenantId);
        return phrases.stream().anyMatch(lower::contains);
    }

    /**
     * Asynchronously generates a call summary via LLM and stores it in the call session.
     */
    private void generateCallSummaryAsync(String callSid, Long tenantId) {
        CompletableFuture.runAsync(() -> {
            try {
                List<ChatMessage> history = conversationStore.getHistory(callSid);
                if (history.isEmpty()) return;

                String transcript = new java.util.ArrayList<>(history).stream()
                        .map(m -> m.getRole() + ": " + m.getContent())
                        .collect(Collectors.joining("\n"));

                String summaryPrompt = "Summarize this phone call in 2-3 sentences. Include: " +
                        "caller name (if known), intent, key details (date/time/doctor), " +
                        "outcome (booked/cancelled/rescheduled/info only), and any follow-up needed.\n\n" +
                        transcript;

                String summary = llmFlowService.generateSummary(summaryPrompt);
                if (summary != null && !summary.isBlank()) {
                    callSessionService.setSummary(callSid, summary);
                }
            } catch (Exception e) {
                log.error("[{}] Failed to generate call summary", callSid, e);
            }
        });
    }

    private CallSession.Outcome mapIntentToOutcome(PendingActionDto.Intent intent) {
        return switch (intent) {
            case BOOK -> CallSession.Outcome.BOOKED;
            case CANCEL -> CallSession.Outcome.CANCELLED;
            case RESCHEDULE -> CallSession.Outcome.RESCHEDULED;
        };
    }

    private boolean isHangupRequest(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase().trim();
        return lower.equals("hangup") || lower.equals("hang up") || lower.equals("bye") || 
               lower.equals("goodbye") || lower.equals("end call") || lower.equals("exit") ||
               lower.contains("hang up");
    }

    private String mapIntentToAction(PendingActionDto.Intent intent) {
        return switch (intent) {
            case BOOK -> "booking";
            case CANCEL -> "cancellation";
            case RESCHEDULE -> "rescheduling";
        };
    }
}
