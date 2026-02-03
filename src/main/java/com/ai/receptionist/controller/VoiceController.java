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

    @Value("${twilio.media-stream-url}")
    private String mediaStreamUrl;

    @Value("${twilio.base-url}")
    private String baseUrl;

    // ✅ ONLY inbound endpoint Twilio calls
    @PostMapping(value = "/twilio/voice/inbound", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> inbound() {
    	String sayTwiml =
    			  "<Say voice=\"" + escapeXml(VOICE) + "\">" +
    			  "Hello, this is the AI Clinic assistant. Would you like to book a doctor appointment today?" +
    			  "</Say>";

        String connect =
            "<Connect><Stream url=\"" + escapeXml(mediaStreamUrl) + "\"/></Connect>";
        log.info("Inbound call -> stream to {}", mediaStreamUrl);
        return ResponseEntity.ok("<Response>" + sayTwiml + connect + "</Response>");
    }

    @RequestMapping(
        value = "/twilio/voice/say",
        method = {RequestMethod.GET, RequestMethod.POST},
        produces = MediaType.APPLICATION_XML_VALUE
    )
    public ResponseEntity<String> say(
            @RequestParam("text") String text,
            @RequestParam(value = "end", required = false) String end) {

        if (!StringUtils.hasText(text)) {
            return ResponseEntity.badRequest()
                .body("<Response><Say>No text.</Say></Response>");
        }

        boolean endCall = "1".equals(end) || "true".equalsIgnoreCase(end);
        String redirectPath =
            endCall ? "/twilio/voice/goodbye" : "/twilio/voice/continue-call";

        String redirectUrl = baseUrl.replaceAll("/$", "") + redirectPath;

        return ResponseEntity.ok(
            "<Response>" +
            "<Say voice=\"" + escapeXml(VOICE) + "\">" + escapeXml(text) + "</Say>" +
            "<Redirect>" + escapeXml(redirectUrl) + "</Redirect>" +
            "</Response>"
        );
    }

    @RequestMapping(
        value = "/twilio/voice/continue-call",
        method = {RequestMethod.GET, RequestMethod.POST},
        produces = MediaType.APPLICATION_XML_VALUE
    )
    public ResponseEntity<String> continueCall() {
        log.info("Continue call -> re-connect stream");
        return ResponseEntity.ok(
            "<Response><Connect><Stream url=\"" +
            escapeXml(mediaStreamUrl) +
            "\"/></Connect></Response>"
        );
    }

    @RequestMapping(
        value = "/twilio/voice/goodbye",
        method = {RequestMethod.GET, RequestMethod.POST},
        produces = MediaType.APPLICATION_XML_VALUE
    )
    public ResponseEntity<String> goodbye() {
        log.info("Conversation ended -> hanging up call");
        return ResponseEntity.ok(
            "<Response><Say voice=\"" + escapeXml(VOICE) +
            "\">Thank you, goodbye.</Say><Hangup/></Response>"
        );
    }

    private static String escapeXml(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;");
    }
}

