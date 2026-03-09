package com.ai.receptionist.service;

import com.ai.receptionist.component.ConversationStore;
import com.ai.receptionist.dto.PendingActionDto;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.exception.LlmException;
import com.ai.receptionist.exception.SttException;
import com.ai.receptionist.utils.YesNoResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationOrchestratorTest {

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

    private static final String CALL_SID = "CA_test123";
    private static final String FROM = "+1234567890";
    private static final Long TENANT_ID = 1L;
    private static final byte[] AUDIO = new byte[]{1, 2, 3};

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
        when(conversationStore.getHistory(anyString())).thenReturn(new ArrayList<>());
    }

    @Test
    void sttFailure_callerHearsRetryMessage() {
        when(sttService.transcribe(any())).thenThrow(new SttException("Whisper error", null));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(twilioService).speakResponse(eq(CALL_SID),
                contains("trouble hearing"), eq(false), eq(TENANT_ID));
        verify(llmFlowService, never()).generateReply(any(), any(), any(), any());
    }

    @Test
    void sttReturnsBlank_nothingHappens() {
        when(sttService.transcribe(any())).thenReturn("");

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(twilioService, never()).speakResponse(any(), any(), anyBoolean(), any());
        verify(llmFlowService, never()).generateReply(any(), any(), any(), any());
    }

    @Test
    void llmFailure_callerHearsFallbackMessage() {
        when(sttService.transcribe(any())).thenReturn("hello");
        when(pendingActionService.getIfAwaitingConfirmation(any())).thenReturn(Optional.empty());
        when(yesNoClassifier.classify(any())).thenReturn(YesNoResult.UNKNOWN);
        when(llmFlowService.generateReply(any(), any(), any(), any()))
                .thenThrow(new LlmException("API error", null));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(twilioService).speakResponse(eq(CALL_SID),
                contains("technical moment"), eq(false), eq(TENANT_ID));
    }

    @Test
    void pendingActionAndYes_executionServiceCalled() {
        when(sttService.transcribe(any())).thenReturn("yes");
        PendingActionDto pending = PendingActionDto.builder()
                .intent(PendingActionDto.Intent.BOOK)
                .awaitingConfirmation(true)
                .build();
        when(pendingActionService.getIfAwaitingConfirmation(CALL_SID))
                .thenReturn(Optional.of(pending));
        when(yesNoClassifier.classify("yes")).thenReturn(YesNoResult.YES);
        when(confirmationExecutionService.execute(eq(CALL_SID), eq(FROM), eq(TENANT_ID), eq(pending)))
                .thenReturn(Optional.of("Your appointment is confirmed!"));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(confirmationExecutionService).execute(CALL_SID, FROM, TENANT_ID, pending);
        verify(pendingActionService).clearPending(CALL_SID);
        verify(callSessionService).setOutcome(eq(CALL_SID), any());
        verify(twilioService).speakResponse(eq(CALL_SID), eq("Your appointment is confirmed!"), eq(false), eq(TENANT_ID));
    }

    @Test
    void pendingActionAndNo_pendingClearedCallerHearsNoProblem() {
        when(sttService.transcribe(any())).thenReturn("no");
        PendingActionDto pending = PendingActionDto.builder()
                .intent(PendingActionDto.Intent.BOOK)
                .awaitingConfirmation(true)
                .build();
        when(pendingActionService.getIfAwaitingConfirmation(CALL_SID))
                .thenReturn(Optional.of(pending));
        when(yesNoClassifier.classify("no")).thenReturn(YesNoResult.NO);

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(pendingActionService).clearPending(CALL_SID);
        verify(twilioService).speakResponse(eq(CALL_SID), eq("No problem. What would you like to do?"), eq(false), eq(TENANT_ID));
        verify(llmFlowService, never()).generateReply(any(), any(), any(), any());
    }

    @Test
    void llmReturnsAction_pendingActionStored() {
        when(sttService.transcribe(any())).thenReturn("I want to book an appointment");
        when(pendingActionService.getIfAwaitingConfirmation(any())).thenReturn(Optional.empty());
        when(yesNoClassifier.classify(any())).thenReturn(YesNoResult.UNKNOWN);

        PendingActionDto action = PendingActionDto.builder()
                .intent(PendingActionDto.Intent.BOOK)
                .doctorKey("dr-smith")
                .awaitingConfirmation(true)
                .build();
        when(llmFlowService.generateReply(any(), any(), any(), any()))
                .thenReturn(new LlmFlowService.FlowResponse("Should I book?", action));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(pendingActionService).setPending(CALL_SID, action);
        verify(twilioService).speakResponse(eq(CALL_SID), eq("Should I book?"), eq(false), eq(TENANT_ID));
    }

    @Test
    void farewellDetected_callEnds() {
        when(sttService.transcribe(any())).thenReturn("bye bye");
        when(pendingActionService.getIfAwaitingConfirmation(any())).thenReturn(Optional.empty());
        when(yesNoClassifier.classify(any())).thenReturn(YesNoResult.UNKNOWN);
        when(llmFlowService.generateReply(any(), any(), any(), any()))
                .thenReturn(new LlmFlowService.FlowResponse("Thanks for calling. Take care!", null));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(twilioService).speakResponse(eq(CALL_SID), contains("Take care"), eq(true), eq(TENANT_ID));
        verify(pendingActionService).clearPending(CALL_SID);
        verify(callSessionService).completeCall(CALL_SID);
        verify(endCallCallback).onEndCall(CALL_SID);
    }

    @Test
    void firstUtterance_marksCallInProgress() {
        when(sttService.transcribe(any())).thenReturn("hello");
        when(pendingActionService.getIfAwaitingConfirmation(any())).thenReturn(Optional.empty());
        when(yesNoClassifier.classify(any())).thenReturn(YesNoResult.UNKNOWN);
        when(llmFlowService.generateReply(any(), any(), any(), any()))
                .thenReturn(new LlmFlowService.FlowResponse("Hi, how can I help?", null));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        verify(callSessionService).markInProgress(CALL_SID);
    }

    @Test
    void longText_isTruncated() {
        String longText = "a".repeat(3000);
        when(sttService.transcribe(any())).thenReturn(longText);
        when(pendingActionService.getIfAwaitingConfirmation(any())).thenReturn(Optional.empty());
        when(yesNoClassifier.classify(any())).thenReturn(YesNoResult.UNKNOWN);
        when(llmFlowService.generateReply(any(), any(), any(), any()))
                .thenReturn(new LlmFlowService.FlowResponse("I see.", null));

        orchestrator.processUtterance(CALL_SID, FROM, TENANT_ID, AUDIO, endCallCallback);

        // Verify the truncated text (2000 chars) was stored
        verify(conversationStore).appendUser(eq(CALL_SID), eq(FROM), argThat(s -> s.length() == 2000));
    }
}
