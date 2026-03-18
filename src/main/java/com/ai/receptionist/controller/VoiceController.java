package com.ai.receptionist.controller;

import com.ai.receptionist.component.AudioPlaybackCache;
import com.ai.receptionist.component.ResponsePhrases;
import com.ai.receptionist.dto.OutboundCallRequest;
import com.ai.receptionist.entity.Tenant;
import com.ai.receptionist.exception.TtsException;
import com.ai.receptionist.service.CallSessionService;
import com.ai.receptionist.service.ElevenLabsVoiceService;
import com.ai.receptionist.service.OutboundCallService;
import com.ai.receptionist.service.TenantService;
import com.ai.receptionist.utils.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;



import java.util.Map;

@RestController
public class VoiceController {

    private static final Logger log = LoggerFactory.getLogger(VoiceController.class);

    private static final String VOICE = "Polly.Joanna-Neural";

    @Value("${twilio.media-stream-url}")
    private String mediaStreamUrl;

    @Value("${twilio.base-url}")
    private String baseUrl;

    @Value("${elevenlabs.voice.enabled}")
    private boolean elevenLabsVoiceEnabled;

    private final ResponsePhrases responsePhrases;
    private final ElevenLabsVoiceService elevenLabsVoiceService;
    private final AudioPlaybackCache audioPlaybackCache;
    private final TenantService tenantService;
    private final CallSessionService callSessionService;
    private final OutboundCallService outboundCallService;

    public VoiceController(ResponsePhrases responsePhrases,
                           ElevenLabsVoiceService elevenLabsVoiceService,
                           AudioPlaybackCache audioPlaybackCache,
                           TenantService tenantService,
                           CallSessionService callSessionService,
                           OutboundCallService outboundCallService) {
        this.responsePhrases = responsePhrases;
        this.elevenLabsVoiceService = elevenLabsVoiceService;
        this.audioPlaybackCache = audioPlaybackCache;
        this.tenantService = tenantService;
        this.callSessionService = callSessionService;
        this.outboundCallService = outboundCallService;
    }

    @PostMapping(value = "/inbound", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> inbound(@RequestParam(required = false) Map<String, String> params) {
        return inboundTwiMl(params);
    }

    @PostMapping(value = "/twilio/voice/inbound", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> twilioVoiceInbound(@RequestParam(required = false) Map<String, String> params) {
        return inboundTwiMl(params);
    }

    private ResponseEntity<String> inboundTwiMl(Map<String, String> params) {
        String from = params != null ? params.getOrDefault("From", "") : "";
        String to = params != null ? params.getOrDefault("To", "") : "";
        String callSid = params != null ? params.getOrDefault("CallSid", "") : "";

        // Resolve tenant by the Twilio number that received the call
        Tenant tenant = tenantService.resolveTenant(to);

        if (tenant == null) {
            log.error("Tenant resolution failed for Twilio number {}", to);
        } else {
            log.info("Tenant resolved successfully: id={} slug={} phone={}",
                    tenant.getId(),
                    tenant.getSlug(),
                    to);
        }
        
        if (tenant == null) {
            log.error("No tenant found for Twilio number: {}", to);
            return ResponseEntity.ok(
                "<Response><Say>Service unavailable.</Say><Hangup/></Response>"
            );
        }

        // Track call session
        if (StringUtils.hasText(callSid)) {
        	log.info("Creating call session: tenantId={} callSid={} from={} to={}",
        	        tenant.getId(),
        	        callSid,
        	        LogSanitizer.maskPhone(from),
        	        to);

        	callSessionService.createInbound(tenant.getId(), callSid, from, to);
        }

        String playOrSayTwiml = buildPlaybackTwiml(responsePhrases.greeting(tenant.getId()));
        StringBuilder streamParamsBuilder = new StringBuilder();
        if (StringUtils.hasText(from)) {
            streamParamsBuilder.append("<Parameter name=\"From\" value=\"").append(escapeXml(from)).append("\"/>");
        }
        // Pass tenantId to the media stream so the orchestrator knows which tenant this call belongs to
        streamParamsBuilder.append("<Parameter name=\"TenantId\" value=\"").append(tenant.getId()).append("\"/>");

        String connectTwiml = "<Connect><Stream url=\"" + escapeXml(mediaStreamUrl) + "\">" + streamParamsBuilder + "</Stream></Connect>";
        String twiml = "<Response>" + playOrSayTwiml + connectTwiml + "</Response>";
        log.info("Inbound call -> stream to {} | callSid={} from={} tenant={}", mediaStreamUrl, callSid, LogSanitizer.maskPhone(from), tenant.getSlug());
        return ResponseEntity.ok(twiml);
    }

    @RequestMapping(value = "/twilio/voice/say", method = {RequestMethod.GET, RequestMethod.POST}, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> say(
            @RequestParam("text") String text,
            @RequestParam(value = "end", required = false) String end,
            @RequestParam(value = "TenantId", required = false) String tenantId) {

        if (!StringUtils.hasText(text)) {
            return ResponseEntity.badRequest().body("<Response><Say>No text.</Say></Response>");
        }

        boolean endCall = "1".equals(end) || "true".equalsIgnoreCase(end != null ? end : "");

        String playOrSayTwiml = buildPlaybackTwiml(text);

        if (endCall) {
            String twiml = "<Response>" + playOrSayTwiml + "<Hangup/></Response>";
            log.info("Conversation ended -> hanging up call");
            return ResponseEntity.ok(twiml);
        }

        String redirectUrl =
                baseUrl + "/twilio/voice/continue-call?TenantId=" + tenantId;

        String twiml =
                "<Response>" +
                playOrSayTwiml +
                "<Redirect>" + escapeXml(redirectUrl) + "</Redirect>" +
                "</Response>";

        return ResponseEntity.ok(twiml);
    }

    /**
     * Uses ElevenLabs when enabled and API key is set; otherwise falls back to Twilio Polly.
     */
    private String buildPlaybackTwiml(String text) {
        if (elevenLabsVoiceEnabled && elevenLabsVoiceService != null) {
            try {
                byte[] audio = elevenLabsVoiceService.synthesize(text);
                if (audio != null && audio.length > 0) {
                    String playbackId = audioPlaybackCache.put(audio);
                    if (playbackId != null && StringUtils.hasText(baseUrl)) {
                        String playUrl = baseUrl.trim().replaceAll("/$", "") + "/audio/play/" + playbackId;
                        return "<Play>" + escapeXml(playUrl) + "</Play>";
                    }
                }
            } catch (TtsException e) {
                log.warn("ElevenLabs TTS failed, falling back to Polly: {}", e.getMessage());
            }
        }
        // Fallback to Twilio's built-in Polly TTS
        return "<Say voice=\"" + escapeXml(VOICE) + "\"><prosody rate=\"1.1\">" + escapeXml(text) + "</prosody></Say>";
    }

    @RequestMapping(value = "/twilio/voice/goodbye", method = {RequestMethod.GET, RequestMethod.POST}, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> goodbye() {
        String playOrSayTwiml = buildPlaybackTwiml("Thank you, goodbye.");
        String twiml = "<Response>" + playOrSayTwiml + "<Hangup/></Response>";
        log.info("Conversation ended -> hanging up call");
        return ResponseEntity.ok(twiml);
    }

    @PostMapping(value = "/continue-call", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> continueCall(@RequestParam Map<String,String> params) {
        return continueCallTwiMl(params);
    }

    @RequestMapping(value = "/twilio/voice/continue-call",
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> twilioVoiceContinueCall(
            @RequestParam Map<String,String> params) {

        return continueCallTwiMl(params);
    }

    /**
     * After AI speaks: re-connect stream only (silent — no spoken phrase).
     * Do NOT redirect to inbound (that would replay the greeting).
     */
    private ResponseEntity<String> continueCallTwiMl(Map<String,String> params) {

        String tenantId = params.getOrDefault("TenantId", "");

        StringBuilder streamParams = new StringBuilder();

        if (StringUtils.hasText(tenantId)) {
            streamParams.append("<Parameter name=\"TenantId\" value=\"")
                    .append(escapeXml(tenantId))
                    .append("\"/>");
        }

        String connectTwiml =
                "<Connect><Stream url=\"" + escapeXml(mediaStreamUrl) + "\">"
                        + streamParams +
                        "</Stream></Connect>";

        String twiml = "<Response>" + connectTwiml + "</Response>";

        log.info("Continue call -> reconnect stream tenantId={}", tenantId);

        return ResponseEntity.ok(twiml);
    }

    /**
     * Outbound call webhook — Twilio calls this URL when the outbound call connects.
     * Generates TwiML to greet the callee and connect a media stream.
     */
    @PostMapping(value = "/twilio/voice/outbound-start", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> outboundStart(@RequestParam Map<String, String> params) {
        String tenantIdParam = params.getOrDefault("tenantId", "");
        String patientName = params.getOrDefault("patientName", "");
        String doctorName = params.getOrDefault("doctorName", "");
        String date = params.getOrDefault("date", "");
        String time = params.getOrDefault("time", "");

        Long tenantId = null;
        try { tenantId = Long.parseLong(tenantIdParam); } catch (NumberFormatException ignored) {}

        // Build outbound greeting with appointment context
        String greeting;
        if (!patientName.isBlank() && !doctorName.isBlank()) {
            String aiName = tenantId != null ? tenantService.getConfig(tenantId, "ai_name", "Sarah") : "Sarah";
            greeting = String.format("Hi %s, this is %s calling from the clinic. " +
                            "Just a reminder: you have an appointment with %s today at %s. " +
                            "Do you have any questions about your appointment? If not, you can say \"no, thank you\" and I'll let you go.",
                    patientName, aiName, doctorName, time);
        } else {
            greeting = responsePhrases.greeting(tenantId);
        }

        String playOrSayTwiml = buildPlaybackTwiml(greeting);
        StringBuilder streamParams = new StringBuilder();
        if (tenantId != null) {
            streamParams.append("<Parameter name=\"TenantId\" value=\"").append(tenantId).append("\"/>");
        }

        String connectTwiml = "<Connect><Stream url=\"" + escapeXml(mediaStreamUrl) + "\">" + streamParams + "</Stream></Connect>";
        String twiml = "<Response>" + playOrSayTwiml + connectTwiml + "</Response>";
        log.info("Outbound call connected -> stream to {} | tenant={}", mediaStreamUrl, tenantIdParam);
        return ResponseEntity.ok(twiml);
    }

    /**
     * API endpoint to trigger an outbound call.
     */
    @PostMapping("/api/outbound/call")
    public ResponseEntity<Map<String, String>> triggerOutboundCall(@RequestBody OutboundCallRequest request) {
        String callSid = outboundCallService.initiateCall(
                request.getTenantId(), request.getToNumber(), request.getContext());
        return ResponseEntity.ok(Map.of("callSid", callSid != null ? callSid : ""));
    }

    /**
     * Twilio status callback — receives call lifecycle events.
     */
    @PostMapping("/twilio/voice/status")
    public ResponseEntity<Void> twilioStatusCallback(@RequestParam Map<String, String> params) {
        String callSid = params.getOrDefault("CallSid", "");
        String callStatus = params.getOrDefault("CallStatus", "");
        String duration = params.get("CallDuration");
        if (StringUtils.hasText(callSid) && StringUtils.hasText(callStatus)) {
            callSessionService.updateFromTwilioStatus(callSid, callStatus, duration);
        }
        return ResponseEntity.ok().build();
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
