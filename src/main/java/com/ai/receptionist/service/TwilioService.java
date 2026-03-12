package com.ai.receptionist.service;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.ai.receptionist.dto.TwilioCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
public class TwilioService {

    private static final Logger log = LoggerFactory.getLogger(TwilioService.class);
    private static final String TWILIO_API_BASE = "https://api.twilio.com/2010-04-01";
    private static final String SAY_PATH = "/twilio/voice/say";

    @Value("${twilio.base-url}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final TenantService tenantService;

    public TwilioService(@Qualifier("twilioRestTemplate") RestTemplate restTemplate,
                         TenantService tenantService) {
        this.restTemplate = restTemplate;
        this.tenantService = tenantService;
    }

    /**
     * Speak response without ending call (uses per-tenant Twilio credentials).
     */
    public void speakResponse(String callSid, String text, Long tenantId) {
        speakResponse(callSid, text, false, tenantId);
    }

    /**
     * Speak response and optionally end call AFTER playback.
     * Uses per-tenant Twilio credentials resolved via TenantService.
     */
    public void speakResponse(String callSid, String text, boolean endCall, Long tenantId) {

        if (callSid == null || text == null || text.isBlank()) {
            return;
        }

        TwilioCredentials creds = tenantService.getTwilioCredentials(tenantId);
        if (!creds.isValid()) {
            log.warn("Twilio credentials missing for tenantId={} — skipping speakResponse", tenantId);
            return;
        }

        try {
        	String sayUrl = buildSayUrl(text, endCall, tenantId);

            String apiUrl = TWILIO_API_BASE +
                    "/Accounts/" + creds.accountSid() +
                    "/Calls/" + callSid + ".json";

            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(creds.accountSid(), creds.authToken());
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("Url", sayUrl);

            HttpEntity<MultiValueMap<String, String>> request =
                    new HttpEntity<>(body, headers);

            ResponseEntity<String> response =
                    restTemplate.postForEntity(apiUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Twilio call update returned {} for call {}",
                        response.getStatusCode(), callSid);
            }

        } catch (HttpClientErrorException e) {
            // 21220 = "Call is not in-progress" — caller hung up before AI response was ready.
            // This is a normal race condition, not an error.
            if (e.getStatusCode().value() == 400
                    && e.getResponseBodyAsString().contains("21220")) {
                log.info("Call {} already ended (caller hung up) — skipping speak response", callSid);
            } else {
                log.error("Twilio client error for call {}: {}", callSid, e.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to send Twilio speak response for call {}", callSid, e);
        }
    }

    /**
     * Force hangup call immediately using per-tenant credentials.
     */
    public void hangupCall(String callSid, Long tenantId) {
        if (callSid == null || callSid.isBlank()) return;

        TwilioCredentials creds = tenantService.getTwilioCredentials(tenantId);
        if (!creds.isValid()) return;

        try {
            String apiUrl = TWILIO_API_BASE +
                    "/Accounts/" + creds.accountSid() +
                    "/Calls/" + callSid + ".json";

            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(creds.accountSid(), creds.authToken());

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("Status", "completed");

            HttpEntity<MultiValueMap<String, String>> request =
                    new HttpEntity<>(body, headers);

            restTemplate.postForEntity(apiUrl, request, String.class);

            log.info("Call terminated: {}", callSid);

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 400
                    && e.getResponseBodyAsString().contains("21220")) {
                log.info("Call {} already ended — hangup not needed", callSid);
            } else {
                log.warn("Failed to hang up call {}: {}", callSid, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Failed to hang up call {}: {}", callSid, e.getMessage());
        }
    }

    /**
     * Builds Twilio say URL.
     * If endCall=true, TwiML will hang up AFTER speech finishes.
     */
    private String buildSayUrl(String text, boolean endCall, Long tenantId) {

        String encoded;

        try {
            encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            encoded = text.replace(" ", "+");
        }

        String base = (baseUrl != null && !baseUrl.isBlank())
                ? baseUrl.replaceAll("/$", "")
                : "";

        String url = base + SAY_PATH + "?text=" + encoded;

        if (tenantId != null) {
            url += "&TenantId=" + tenantId;
        }

        if (endCall) {
            url += "&end=1";
        }

        return url;
    }
}
