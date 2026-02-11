package com.ai.receptionist.service;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.web.client.RestTemplateBuilder;
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
     * Updates the active call so Twilio fetches TwiML that speaks the AI response
     * then redirects to /continue-call or /goodbye (hang up) if endCall is true.
     */
    public void speakResponse(String callSid, String aiText) {
        speakResponse(callSid, aiText, false);
    }

    /**
     * Same as speakResponse(callSid, aiText). When endCall is true, after speaking
     * the call hangs up. Also schedules explicit REST API hangup to ensure call terminates
     * (stream redirect alone can leave call connected).
     */
    public void speakResponse(String callSid, String aiText, boolean endCall) {
        if (callSid == null || aiText == null || aiText.isEmpty()) {
            return;
        }
        if (accountSid == null || accountSid.isEmpty() || authToken == null || authToken.isEmpty()) {
            log.warn("Twilio credentials not set; skipping speakResponse");
            return;
        }
        String sayUrl = buildSayUrl(aiText, endCall);
        String apiUrl = TWILIO_API_BASE + "/Accounts/" + accountSid + "/Calls/" + callSid + ".json";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(accountSid, authToken);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("Url", sayUrl);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);
            if (response.getStatusCode() != HttpStatus.OK) {
                log.warn("Twilio Call Update returned {} for call {}", response.getStatusCode(), callSid);
            }
        } catch (Exception e) {
            log.error("Twilio Call Update failed for call {}", callSid, e);
        }

        // When endCall=true, redirect URL returns TwiML with <Hangup/>. Do NOT call REST DELETE -
        // it causes 409 "Call cannot be deleted because it is still in progress" while TwiML is executing.
    }

    public void hangupCall(String callSid) {
        if (callSid == null || accountSid == null || accountSid.isEmpty() || authToken == null || authToken.isEmpty()) {
            return;
        }
        String apiUrl = TWILIO_API_BASE + "/Accounts/" + accountSid + "/Calls/" + callSid + ".json";
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(accountSid, authToken);
        try {
            restTemplate.exchange(apiUrl, org.springframework.http.HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
            log.info("Terminated call via REST API: {}", callSid);
        } catch (Exception e) {
            log.warn("Twilio hangup failed for call {}: {}", callSid, e.getMessage());
        }
    }

    private String buildSayUrl(String text, boolean endCall) {
        String encoded;
        try {
            encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            encoded = text.replace(" ", "+");
        }
        String base = (baseUrl != null && !baseUrl.isEmpty())
            ? baseUrl.trim().replaceAll("/$", "")
            : "";
        String url = base + SAY_PATH + "?text=" + encoded;
        if (endCall) {
            url = url + "&end=1";
        }
        return url;
    }
}
