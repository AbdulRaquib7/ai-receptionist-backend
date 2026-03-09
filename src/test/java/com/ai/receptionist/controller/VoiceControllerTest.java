package com.ai.receptionist.controller;

import com.ai.receptionist.component.AudioPlaybackCache;
import com.ai.receptionist.component.ResponsePhrases;
import com.ai.receptionist.entity.Tenant;
import com.ai.receptionist.service.CallSessionService;
import com.ai.receptionist.service.ElevenLabsVoiceService;
import com.ai.receptionist.service.OutboundCallService;
import com.ai.receptionist.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VoiceController.class)
@TestPropertySource(properties = {
    "twilio.media-stream-url=wss://localhost:8080/media-stream",
    "twilio.base-url=http://localhost:8080",
    "elevenlabs.voice.enabled=false"
})
class VoiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private ResponsePhrases responsePhrases;
    @MockBean private ElevenLabsVoiceService elevenLabsVoiceService;
    @MockBean private AudioPlaybackCache audioPlaybackCache;
    @MockBean private TenantService tenantService;
    @MockBean private CallSessionService callSessionService;
    @MockBean private OutboundCallService outboundCallService;

    private Tenant defaultTenant() {
        return Tenant.builder().id(1L).name("Test").slug("test").twilioPhone("+10000000000").build();
    }

    @Test
    void shouldReturnTwiMlOnInbound() throws Exception {
        when(tenantService.resolveTenant(anyString())).thenReturn(defaultTenant());
        when(responsePhrases.greeting(1L)).thenReturn("Hello, welcome!");

        mockMvc.perform(post("/inbound")
                .param("From", "+1234567890")
                .param("To", "+10000000000")
                .param("CallSid", "CA123"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("<Response>")))
                .andExpect(content().string(containsString("Hello, welcome!")))
                .andExpect(content().string(containsString("<Connect><Stream url=\"wss://localhost:8080/media-stream\">")));

        verify(callSessionService).createInbound(eq(1L), eq("CA123"), eq("+1234567890"), eq("+10000000000"));
    }

    @Test
    void shouldReturnTwiMlForContinueCall() throws Exception {
        mockMvc.perform(post("/continue-call"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("<Response><Connect><Stream url=\"wss://localhost:8080/media-stream\"/></Connect></Response>")));
    }

    @Test
    void shouldReturnTwiMlForSay() throws Exception {
        mockMvc.perform(get("/twilio/voice/say").param("text", "Just saying hello"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Just saying hello")))
                .andExpect(content().string(containsString("<Redirect>")));
    }

    @Test
    void shouldReturnTwiMlWithHangupOnEndForSay() throws Exception {
        mockMvc.perform(get("/twilio/voice/say")
                .param("text", "Goodbye")
                .param("end", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Goodbye")))
                .andExpect(content().string(containsString("<Hangup/>")));
    }

    @Test
    void shouldReturnTwiMlForGoodbye() throws Exception {
        mockMvc.perform(get("/twilio/voice/goodbye"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Thank you, goodbye.")))
                .andExpect(content().string(containsString("<Hangup/>")));
    }

    @Test
    void shouldHandleStatusCallback() throws Exception {
        mockMvc.perform(post("/twilio/voice/status")
                .param("CallSid", "CA456")
                .param("CallStatus", "completed")
                .param("CallDuration", "120"))
                .andExpect(status().isOk());

        verify(callSessionService).updateFromTwilioStatus("CA456", "completed", "120");
    }

    @Test
    void shouldHandleOutboundStart() throws Exception {
        when(tenantService.getConfig(eq(1L), eq("ai_name"), anyString())).thenReturn("Sarah");

        mockMvc.perform(post("/twilio/voice/outbound-start")
                .param("tenantId", "1")
                .param("patientName", "John")
                .param("doctorName", "Dr Smith")
                .param("date", "2025-01-20")
                .param("time", "10:00 AM"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<Response>")))
                .andExpect(content().string(containsString("John")))
                .andExpect(content().string(containsString("Dr Smith")));
    }
}
