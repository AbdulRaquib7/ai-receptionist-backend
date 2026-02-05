package com.ai.receptionist.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.twilio.Twilio;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;

import jakarta.annotation.PostConstruct;

@RestController
public class TokenController {

    @Value("${twilio.accountSid}")
    private String accountSid;

    @Value("${twilio.apiKeySid}")
    private String apiKeySid;

    @Value("${twilio.apiKeySecret}")
    private String apiKeySecret;

    @PostConstruct
    void init() {
        Twilio.init(apiKeySid, apiKeySecret);
    }

    @GetMapping("/token")
    public Map<String, String> token() {
        VoiceGrant grant = new VoiceGrant();
        grant.setOutgoingApplicationSid("APfb98e926896212a5575c4f905c356c84");

        AccessToken token = new AccessToken.Builder(
                accountSid,
                apiKeySid,
                apiKeySecret
        ).grant(grant).build();

        return Map.of("token", token.toJwt());
    }
    

}

