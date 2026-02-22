package com.ai.receptionist.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VoiceController {

    private static final Logger log = LoggerFactory.getLogger(VoiceController.class);

    private static final String VOICE = "Polly.Joanna-Neural";

    @Value("${twilio.media-stream-url:wss://localhost:8080/media-stream}")
    private String mediaStreamUrl;

    @Value("${twilio.base-url:}")
    private String baseUrl;

    @PostMapping(value = "/inbound", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> inbound() {
        return inboundTwiMl();
    }

    @PostMapping(value = "/twilio/voice/inbound", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> twilioVoiceInbound() {
        return inboundTwiMl();
    }

    private ResponseEntity<String> inboundTwiMl() {
        String sayTwiml = "<Say voice=\"" + escapeXml(VOICE) + "\">Hello, how can I help you?</Say>";
        String connectTwiml = "<Connect><Stream url=\"" + escapeXml(mediaStreamUrl) + "\"/></Connect>";
        String twiml = "<Response>" + sayTwiml + connectTwiml + "</Response>";
        log.info("Inbound call -> stream to {}", mediaStreamUrl);
        return ResponseEntity.ok(twiml);
    }

    @RequestMapping(value = "/twilio/voice/say", method = {RequestMethod.GET, RequestMethod.POST}, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> say(
            @RequestParam("text") String text,
            @RequestParam(value = "end", required = false) String end) {
        if (!StringUtils.hasText(text)) {
            return ResponseEntity.badRequest().body("<Response><Say>No text.</Say></Response>");
        }
        boolean endCall = "1".equals(end) || "true".equalsIgnoreCase(end != null ? end : "");
        String redirectPath = endCall ? "/twilio/voice/goodbye" : "/twilio/voice/continue-call";
        String redirectUrl = StringUtils.hasText(baseUrl)
            ? baseUrl.trim().replaceAll("/$", "") + redirectPath
            : redirectPath;
        String sayTwiml = "<Say voice=\"" + escapeXml(VOICE) + "\">" + escapeXml(text) + "</Say>";
        String redirectTwiml = "<Redirect>" + escapeXml(redirectUrl) + "</Redirect>";
        String twiml = "<Response>" + sayTwiml + redirectTwiml + "</Response>";
        return ResponseEntity.ok(twiml);
    }

    @RequestMapping(value = "/twilio/voice/goodbye", method = {RequestMethod.GET, RequestMethod.POST}, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> goodbye() {
        String sayTwiml = "<Say voice=\"" + escapeXml(VOICE) + "\">Thank you, goodbye.</Say>";
        String hangupTwiml = "<Hangup/>";
        String twiml = "<Response>" + sayTwiml + hangupTwiml + "</Response>";
        log.info("Conversation ended -> hanging up call");
        return ResponseEntity.ok(twiml);
    }

    @PostMapping(value = "/continue-call", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> continueCall() {
        return continueCallTwiMl();
    }

    @RequestMapping(value = "/twilio/voice/continue-call", method = {RequestMethod.GET, RequestMethod.POST}, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> twilioVoiceContinueCall() {
        return continueCallTwiMl();
    }

    /**
     * After AI speaks: re-connect stream only (no "I'm listening", no pause).
     * Do NOT redirect to inbound (that would replay "Hello, how can I help you?").
     */
    private ResponseEntity<String> continueCallTwiMl() {
        String connectTwiml = "<Connect><Stream url=\"" + escapeXml(mediaStreamUrl) + "\"/></Connect>";
        String twiml = "<Response>" + connectTwiml + "</Response>";
        log.info("Continue call -> re-connect stream (no I'm listening)");
        return ResponseEntity.ok(twiml);
    }

    private static String escapeXml(String raw) {
        if (raw == null) return "";
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
