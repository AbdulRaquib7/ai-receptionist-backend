package com.ai.receptionist.service;

import com.ai.receptionist.component.ConversationStore;
import com.ai.receptionist.dto.PendingActionDto;
import com.ai.receptionist.entity.CallSession;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.exception.SttException;
import com.ai.receptionist.utils.YesNoResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Simulates an end-to-end inbound call flow without external services.
 * Tests the full conversation pipeline:
 * 1. User says "I want to book an appointment"
 * 2. AI asks for details and proposes booking
 * 3. User confirms with "yes"
 * 4. Appointment is booked
 * 5. AI says farewell → call ends
 */
class CallFlowIntegrationTest {

    @Mock private SttService sttService;
    @Mock private LlmFlowService llmFlowService;
    @Mock private PendingActionService pendingActionService;
    @Mock private ConfirmationExecutionService confirmationExecutionService;
    @Mock private YesNoClassifierService yesNoClassifier;
    @Mock private ConversationStore conversationStore;
    @Mock private TwilioService twilioService;
    @Mock private TenantService tenantService;
    @Mock private CallSessionService callSessionService;
    @Mock private ConversationOrchestrator.EndCallCallback endCallCallback;

    private ConversationOrchestrator orchestrator;

    private static final String CALL_SID = "CA_flow_test";
    private static final String FROM = "+1555123456";
    private static final Long TENANT_ID = 1L;
    private static final byte[] AUDIO = new byte[]{1, 2, 3, 4};

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orchestrator = new ConversationOrchestrator(
                sttService, llmFlowService, pendingActionService,
                confirmationExecutionService, yesNoClassifier,
                conversationStore, twilioService, tenantService, callSessionService
        );
        when(tenantService.getFarewellPhrases(anyLong())).thenReturn(
                List.of("take care", "goodbye", "bye"));
    }

    @Test
    @DisplayName("Full booking flow: request → LLM proposes → user confirms → booked → farewell")
    void fullBookingFlow() {
        // Maintain a shared conversation history
        List<ChatMessage> history = Collections.synchronizedList(new ArrayList<>());
        when(conversationStore.getHistory(CALL_SID)).thenReturn(history);
        doAnswer(inv -> { history.add(new ChatMessage("user", inv.getArgument(2))); return null; })
                .when(conversationStore).appendUser(eq(CALL_SID), eq(FROM), anyString());
        doAnswer(inv -> { history.add(new ChatMessage("assistant", inv.getArgument(2))); return null; })
                .when(conversationStore).appendAssistant(eq(CALL_SID), eq(FROM), anyString());

        // --- Turn 1: User wants to book ---
        when(sttService.transcribe(any())).thenReturn("I want to book an appointment with Dr Arun tomorrow at 9 AM");
        when(pendingActionService.getIfAwaitingConfirmation(CALL_SID)).thenReturn(Optional.empty());
        when(yesNoClassifier.classify(anyString())).thenReturn(YesNoResult.UNKNOWN);

        PendingActionDto bookAction = PendingActionDto.builder()
                .intent(PendingActionDto.Intent.BOOK)
                .doctorKey("dr-arun")
                .date("2025-01-16")
                .time("09:00 AM")
                .patientName("John")
                .awaitingConfirmation(true)
                .build();
        when(llmFlowService.generateReply(eq(CALL_SID), eq(FROM), eq(TENANT_ID), any()))
                .thenReturn(new LlmFlowService.FlowResponse(
                        "I have an appointment with Dr Arun tomorrow at 9 AM. Should I go ahead and book that?",
                        bookAction));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(callSessionService).markInProgress(CALL_SID);
        verify(pendingActionService).setPending(CALL_SID, bookAction);
        verify(twilioService).speakResponse(eq(CALL_SID), contains("Should I go ahead"), eq(false), eq(TENANT_ID));

        // --- Turn 2: User confirms ---
        when(sttService.transcribe(any())).thenReturn("yes please");
        when(pendingActionService.getIfAwaitingConfirmation(CALL_SID)).thenReturn(Optional.of(bookAction));
        when(yesNoClassifier.classify("yes please")).thenReturn(YesNoResult.YES);
        when(confirmationExecutionService.execute(CALL_SID, FROM, TENANT_ID, bookAction))
                .thenReturn(Optional.of("You're all set! Your appointment is confirmed for tomorrow at 9 AM. Take care!"));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(confirmationExecutionService).execute(CALL_SID, FROM, TENANT_ID, bookAction);
        verify(pendingActionService).clearPending(CALL_SID);
        verify(callSessionService).setOutcome(CALL_SID, CallSession.Outcome.BOOKED);
        // The farewell "Take care" should trigger end call
        verify(twilioService).speakResponse(eq(CALL_SID),
                contains("Take care"), eq(true), eq(TENANT_ID));
        verify(callSessionService).completeCall(CALL_SID);
        verify(endCallCallback).onEndCall(CALL_SID);
    }

    @Test
    @DisplayName("STT failure on first utterance → retry prompt, then successful second utterance")
    void sttFailureThenRecovery() {
        List<ChatMessage> history = Collections.synchronizedList(new ArrayList<>());
        when(conversationStore.getHistory(CALL_SID)).thenReturn(history);
        doAnswer(inv -> { history.add(new ChatMessage("user", inv.getArgument(2))); return null; })
                .when(conversationStore).appendUser(eq(CALL_SID), eq(FROM), anyString());
        doAnswer(inv -> { history.add(new ChatMessage("assistant", inv.getArgument(2))); return null; })
                .when(conversationStore).appendAssistant(eq(CALL_SID), eq(FROM), anyString());

        // Turn 1: STT fails
        when(sttService.transcribe(any())).thenThrow(new SttException("Whisper error", null));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(twilioService).speakResponse(eq(CALL_SID), contains("trouble hearing"), eq(false), eq(TENANT_ID));
        verify(llmFlowService, never()).generateReply(any(), any(), any(), any());

        // Turn 2: STT succeeds
        when(sttService.transcribe(any())).thenReturn("hello");
        when(pendingActionService.getIfAwaitingConfirmation(any())).thenReturn(Optional.empty());
        when(yesNoClassifier.classify(any())).thenReturn(YesNoResult.UNKNOWN);
        when(llmFlowService.generateReply(any(), any(), any(), any()))
                .thenReturn(new LlmFlowService.FlowResponse("Hi! How can I help?", null));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(callSessionService).markInProgress(CALL_SID);
        verify(twilioService).speakResponse(eq(CALL_SID), eq("Hi! How can I help?"), eq(false), eq(TENANT_ID));
    }

    @Test
    @DisplayName("Cancel flow: user declines first, then confirms cancellation")
    void cancelFlowWithDeclineThenConfirm() {
        List<ChatMessage> history = Collections.synchronizedList(new ArrayList<>());
        when(conversationStore.getHistory(CALL_SID)).thenReturn(history);
        doAnswer(inv -> { history.add(new ChatMessage("user", inv.getArgument(2))); return null; })
                .when(conversationStore).appendUser(eq(CALL_SID), eq(FROM), anyString());
        doAnswer(inv -> { history.add(new ChatMessage("assistant", inv.getArgument(2))); return null; })
                .when(conversationStore).appendAssistant(eq(CALL_SID), eq(FROM), anyString());

        // Turn 1: LLM proposes cancellation
        when(sttService.transcribe(any())).thenReturn("I need to cancel my appointment");
        when(pendingActionService.getIfAwaitingConfirmation(CALL_SID)).thenReturn(Optional.empty());
        when(yesNoClassifier.classify(any())).thenReturn(YesNoResult.UNKNOWN);

        PendingActionDto cancelAction = PendingActionDto.builder()
                .intent(PendingActionDto.Intent.CANCEL)
                .targetPatientName("John")
                .awaitingConfirmation(true)
                .build();
        when(llmFlowService.generateReply(any(), any(), any(), any()))
                .thenReturn(new LlmFlowService.FlowResponse("Should I cancel your appointment?", cancelAction));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(pendingActionService).setPending(CALL_SID, cancelAction);

        // Turn 2: User says no
        when(sttService.transcribe(any())).thenReturn("no, wait");
        when(pendingActionService.getIfAwaitingConfirmation(CALL_SID)).thenReturn(Optional.of(cancelAction));
        when(yesNoClassifier.classify("no, wait")).thenReturn(YesNoResult.NO);

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(twilioService).speakResponse(eq(CALL_SID), eq("No problem. What would you like to do?"), eq(false), eq(TENANT_ID));
    }
}
