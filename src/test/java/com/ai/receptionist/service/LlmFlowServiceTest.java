package com.ai.receptionist.service;

import com.ai.receptionist.component.CallerPhoneResolver;
import com.ai.receptionist.config.ConversationProperties;
import com.ai.receptionist.dto.PendingActionDto;
import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.entity.Doctor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Tests LlmFlowService response parsing.
 * Mocks the OpenAI API call and verifies parsing of various response formats.
 */
class LlmFlowServiceTest {

    @Mock private RestTemplate restTemplate;
    @Mock private AppointmentService appointmentService;
    @Mock private CallerPhoneResolver callerPhoneResolver;
    @Mock private PromptService promptService;
    @Mock private TenantService tenantService;

    private LlmFlowService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ConversationProperties props = new ConversationProperties();
        service = new LlmFlowService(restTemplate, mapper, appointmentService,
                callerPhoneResolver, props, promptService, tenantService);
        ReflectionTestUtils.setField(service, "openAiApiKey", "test-key");
        ReflectionTestUtils.setField(service, "openAiModel", "gpt-4o-mini");

        // Stub tenant/prompt services
        when(tenantService.getTenantName(anyLong())).thenReturn("Test Practice");
        when(tenantService.getConfig(anyLong(), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(2));
        when(promptService.renderTemplate(anyLong(), anyString(), anyString(), any())).thenAnswer(inv -> inv.getArgument(2));
        when(callerPhoneResolver.resolve(any())).thenReturn("+1234567890");
        when(appointmentService.getAllDoctors(anyLong())).thenReturn(List.of(
                Doctor.builder().id(1L).key("dr-test").name("Dr Test").specialization("General").build()
        ));
        when(appointmentService.getAvailableSlotsForNextWeek(anyLong())).thenReturn(java.util.Map.of());
        when(appointmentService.getUpcomingAppointmentSummaries(any(), anyLong())).thenReturn(List.of());
    }

    private void mockLlmResponse(String content) {
        String json = """
                {"choices":[{"message":{"content":"%s"}}]}
                """.formatted(content.replace("\"", "\\\"").replace("\n", "\\n"));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(json, HttpStatus.OK));
    }

    private void mockLlmResponseRaw(String rawJson) {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(rawJson, HttpStatus.OK));
    }

    @Test
    void validJsonWithMessageAndAction_bothParsed() {
        String llmOutput = "{\\\"message\\\":\\\"Should I book that for you?\\\",\\\"action\\\":{\\\"intent\\\":\\\"BOOK\\\",\\\"doctorKey\\\":\\\"dr-test\\\",\\\"date\\\":\\\"2025-01-15\\\",\\\"time\\\":\\\"09:00 AM\\\",\\\"patientName\\\":\\\"John\\\",\\\"patientPhone\\\":\\\"555-1234\\\"}}";
        mockLlmResponse(llmOutput);

        LlmFlowService.FlowResponse result = service.generateReply("call-1", "+123", 1L, new ArrayList<>());

        assertThat(result.getMessage()).isEqualTo("Should I book that for you?");
        assertThat(result.getAction()).isNotNull();
        assertThat(result.getAction().getIntent()).isEqualTo(PendingActionDto.Intent.BOOK);
        assertThat(result.getAction().getDoctorKey()).isEqualTo("dr-test");
    }

    @Test
    void validJsonWithMessageAndNullAction_messageOnly() {
        String llmOutput = "{\\\"message\\\":\\\"How can I help you today?\\\",\\\"action\\\":null}";
        mockLlmResponse(llmOutput);

        LlmFlowService.FlowResponse result = service.generateReply("call-1", "+123", 1L, new ArrayList<>());

        assertThat(result.getMessage()).isEqualTo("How can I help you today?");
        assertThat(result.getAction()).isNull();
    }

    @Test
    void emptyResponse_fallbackMessage() {
        mockLlmResponse("");

        LlmFlowService.FlowResponse result = service.generateReply("call-1", "+123", 1L, new ArrayList<>());

        assertThat(result.getMessage()).isNotBlank();
        assertThat(result.getAction()).isNull();
    }

    @Test
    void markdownWrappedJson_unwrappedAndParsed() {
        String llmOutput = "```json\\n{\\\"message\\\":\\\"Hello there!\\\",\\\"action\\\":null}\\n```";
        mockLlmResponse(llmOutput);

        LlmFlowService.FlowResponse result = service.generateReply("call-1", "+123", 1L, new ArrayList<>());

        assertThat(result.getMessage()).isEqualTo("Hello there!");
        assertThat(result.getAction()).isNull();
    }

    @Test
    void plainTextResponse_treatedAsMessage() {
        mockLlmResponse("Just a plain text reply without JSON");

        LlmFlowService.FlowResponse result = service.generateReply("call-1", "+123", 1L, new ArrayList<>());

        assertThat(result.getMessage()).contains("Just a plain text reply");
        assertThat(result.getAction()).isNull();
    }

    @Test
    void cancelAction_parsedCorrectly() {
        String llmOutput = "{\\\"message\\\":\\\"Should I cancel that appointment?\\\",\\\"action\\\":{\\\"intent\\\":\\\"CANCEL\\\",\\\"targetPatientName\\\":\\\"Jane Doe\\\"}}";
        mockLlmResponse(llmOutput);

        LlmFlowService.FlowResponse result = service.generateReply("call-1", "+123", 1L, new ArrayList<>());

        assertThat(result.getMessage()).isEqualTo("Should I cancel that appointment?");
        assertThat(result.getAction()).isNotNull();
        assertThat(result.getAction().getIntent()).isEqualTo(PendingActionDto.Intent.CANCEL);
        assertThat(result.getAction().getTargetPatientName()).isEqualTo("Jane Doe");
    }

    @Test
    void rescheduleAction_parsedCorrectly() {
        String llmOutput = "{\\\"message\\\":\\\"Shall I reschedule?\\\",\\\"action\\\":{\\\"intent\\\":\\\"RESCHEDULE\\\",\\\"targetPatientName\\\":\\\"John\\\",\\\"doctorKey\\\":\\\"dr-test\\\",\\\"newDate\\\":\\\"2025-02-01\\\",\\\"newTime\\\":\\\"10:00 AM\\\"}}";
        mockLlmResponse(llmOutput);

        LlmFlowService.FlowResponse result = service.generateReply("call-1", "+123", 1L, new ArrayList<>());

        assertThat(result.getAction()).isNotNull();
        assertThat(result.getAction().getIntent()).isEqualTo(PendingActionDto.Intent.RESCHEDULE);
        assertThat(result.getAction().getNewDate()).isEqualTo("2025-02-01");
        assertThat(result.getAction().getNewTime()).isEqualTo("10:00 AM");
    }

    @Test
    void generateSummary_returnsText() {
        String summaryResponse = """
                {"choices":[{"message":{"content":"A caller named John booked an appointment with Dr Test for Jan 15 at 9 AM."}}]}
                """;
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(summaryResponse, HttpStatus.OK));

        String summary = service.generateSummary("Summarize this call...");

        assertThat(summary).contains("John");
        assertThat(summary).contains("Dr Test");
    }

    @Test
    void generateSummary_noApiKey_returnsNull() {
        ReflectionTestUtils.setField(service, "openAiApiKey", "");

        String summary = service.generateSummary("Summarize this call...");

        assertThat(summary).isNull();
    }
}
