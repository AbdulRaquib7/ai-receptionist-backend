package com.ai.receptionist.service;

import com.ai.receptionist.dto.TwilioCredentials;
import com.ai.receptionist.entity.Tenant;
import com.ai.receptionist.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Initiates outbound calls via the Twilio REST API.
 * Uses per-tenant Twilio credentials when available, otherwise falls back to global credentials.
 */
@Service
public class OutboundCallService {

    private static final Logger log = LoggerFactory.getLogger(OutboundCallService.class);
    private static final String TWILIO_API_BASE = "https://api.twilio.com/2010-04-01";

    @Value("${twilio.base-url}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final TenantRepository tenantRepository;
    private final TenantService tenantService;
    private final CallSessionService callSessionService;

    public OutboundCallService(@Qualifier("twilioRestTemplate") RestTemplate restTemplate,
                                TenantRepository tenantRepository,
                                TenantService tenantService,
                                CallSessionService callSessionService) {
        this.restTemplate = restTemplate;
        this.tenantRepository = tenantRepository;
        this.tenantService = tenantService;
        this.callSessionService = callSessionService;
    }

    /**
     * Initiates an outbound call to the given number on behalf of a tenant.
     * Uses per-tenant Twilio credentials if configured, otherwise global credentials.
     *
     * @param tenantId Tenant making the call
     * @param toNumber Phone number to call
     * @param context  Appointment context (patientName, doctorName, date, time, etc.)
     * @return Twilio Call SID
     */
    public String initiateCall(long tenantId, String toNumber, Map<String, String> context) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        TwilioCredentials creds = tenantService.getTwilioCredentials(tenantId);
        if (!creds.isValid()) {
            throw new IllegalStateException("No valid Twilio credentials for tenant " + tenantId);
        }

        String base = baseUrl != null ? baseUrl.replaceAll("/$", "") : "";

        // Build outbound webhook URL with context parameters
        StringBuilder urlBuilder = new StringBuilder(base)
                .append("/twilio/voice/outbound-start?tenantId=").append(tenantId);
        if (context != null) {
            context.forEach((key, value) -> {
                try {
                    urlBuilder.append("&").append(key).append("=")
                            .append(URLEncoder.encode(value, StandardCharsets.UTF_8.name()));
                } catch (UnsupportedEncodingException ignored) {}
            });
        }

        String statusCallbackUrl = base + "/twilio/voice/status";

        String apiUrl = TWILIO_API_BASE + "/Accounts/" + creds.accountSid() + "/Calls.json";

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(creds.accountSid(), creds.authToken());
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("To", toNumber);
        body.add("From", tenant.getTwilioPhone());
        body.add("Url", urlBuilder.toString());
        body.add("StatusCallback", statusCallbackUrl);
        body.add("StatusCallbackEvent", "initiated ringing answered completed");

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl, new HttpEntity<>(body, headers), String.class);

            // Extract CallSid from response (JSON)
            String responseBody = response.getBody();
            String callSid = extractCallSid(responseBody);

            if (callSid != null) {
                callSessionService.createOutbound(tenantId, callSid, tenant.getTwilioPhone(), toNumber);
                log.info("Outbound call initiated: callSid={} to={} tenantId={}", callSid, toNumber, tenantId);
            }

            return callSid;
        } catch (Exception e) {
            log.error("Failed to initiate outbound call to {}: {}", toNumber, e.getMessage());
            throw new RuntimeException("Failed to initiate outbound call", e);
        }
    }

    private String extractCallSid(String responseBody) {
        if (responseBody == null) return null;
        // Simple JSON extraction without pulling in another dependency
        int idx = responseBody.indexOf("\"sid\"");
        if (idx < 0) return null;
        int colon = responseBody.indexOf(':', idx);
        int quote1 = responseBody.indexOf('"', colon + 1);
        int quote2 = responseBody.indexOf('"', quote1 + 1);
        if (quote1 >= 0 && quote2 > quote1) {
            return responseBody.substring(quote1 + 1, quote2);
        }
        return null;
    }
}
