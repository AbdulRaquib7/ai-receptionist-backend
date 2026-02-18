package com.ai.receptionist.service;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
public class TwilioService {

    private static final Logger log = LoggerFactory.getLogger(TwilioService.class);
    private static final String TWILIO_API_BASE = "https://api.twilio.com/2010-04-01";
    private static final String SAY_PATH = "/twilio/voice/say";

    @Value("${twilio.accountSid:${twilio.account-sid:}}")
    private String accountSid;

    @Value("${twilio.authToken:${twilio.auth-token:}}")
    private String authToken;

    @Value("${twilio.base-url:}")
    private String baseUrl;

    private final RestTemplate restTemplate;

    public TwilioService(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    /**
     * Speak response without ending call
     */
    public void speakResponse(String callSid, String text) {
        speakResponse(callSid, text, false);
    }

    /**
     * Speak response and optionally end call AFTER playback.
     */
    public void speakResponse(String callSid, String text, boolean endCall) {

        if (callSid == null || text == null || text.isBlank()) {
            return;
        }

        if (accountSid == null || accountSid.isBlank() ||
            authToken == null || authToken.isBlank()) {
            log.warn("Twilio credentials missing — skipping speakResponse");
            return;
        }

        try {
            String sayUrl = buildSayUrl(text, endCall);

            String apiUrl = TWILIO_API_BASE +
                    "/Accounts/" + accountSid +
                    "/Calls/" + callSid + ".json";

            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(accountSid, authToken);
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

        } catch (Exception e) {
            log.error("Failed to send Twilio speak response for call {}", callSid, e);
        }
    }

    /**
     * Force hangup call immediately
     */
    public void hangupCall(String callSid) {
        if (callSid == null || callSid.isBlank()) return;

        if (accountSid == null || accountSid.isBlank() ||
            authToken == null || authToken.isBlank()) {
            return;
        }

        try {
            String apiUrl = TWILIO_API_BASE +
                    "/Accounts/" + accountSid +
                    "/Calls/" + callSid + ".json";

            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(accountSid, authToken);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("Status", "completed");

            HttpEntity<MultiValueMap<String, String>> request =
                    new HttpEntity<>(body, headers);

            restTemplate.postForEntity(apiUrl, request, String.class);

            log.info("Call terminated: {}", callSid);

        } catch (Exception e) {
            log.warn("Failed to hang up call {}: {}", callSid, e.getMessage());
        }
    }

    /**
     * Builds Twilio say URL.
     * If endCall=true, TwiML will hang up AFTER speech finishes.
     */
    private String buildSayUrl(String text, boolean endCall) {

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

        if (endCall) {
            url += "&end=1";
        }

        return url;
    }
}
