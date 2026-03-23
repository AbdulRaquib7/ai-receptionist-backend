package com.ai.receptionist.realtime;

import com.ai.receptionist.component.CallerPhoneResolver;
import com.ai.receptionist.config.RealtimeApiProperties;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.service.AppointmentService;
import com.ai.receptionist.service.PromptService;
import com.ai.receptionist.service.ReminderCallContextService;
import com.ai.receptionist.service.TenantService;
import com.ai.receptionist.utils.SlotFormattingUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Builds the session.update configuration payload for the OpenAI Realtime API.
 * This includes: system instructions (reusing the same context as LlmFlowService),
 * tool definitions (book/cancel/reschedule), and VAD settings.
 */
@Component
@RequiredArgsConstructor
public class RealtimeSessionConfig {

    private final AppointmentService appointmentService;
    private final PromptService promptService;
    private final TenantService tenantService;
    private final CallerPhoneResolver callerPhoneResolver;
    private final RealtimeApiProperties realtimeProps;
    private final ReminderCallContextService reminderCallContextService;

    /**
     * Build the full session configuration map to send via session.update.
     *
     * @param tenantId    tenant for context
     * @param fromNumber  caller's phone number (for inbound) or callee/patient phone (for outbound reminder)
     * @param callSid     Twilio call SID (to detect reminder calls)
     * @return map suitable for RealtimeApiClient.sendSessionUpdate()
     */
    public Map<String, Object> buildSessionConfig(Long tenantId, String fromNumber, String callSid) {
        Map<String, Object> session = new LinkedHashMap<>();

        // Voice
        String voice = tenantService.getConfig(tenantId, "realtime_voice", realtimeProps.getVoice());
        session.put("voice", voice);

        // Instructions (system prompt)
        boolean isReminderCall = callSid != null && reminderCallContextService.isReminderCall(callSid);
        session.put("instructions", buildSystemInstructions(tenantId, fromNumber, isReminderCall));

        // Input audio format: PCM16 24kHz (we convert from mu-law before sending)
        session.put("input_audio_format", "pcm16");

        // Output audio format: PCM16 24kHz (we convert to mu-law before sending to Twilio)
        session.put("output_audio_format", "pcm16");

        // Input audio transcription (for logging)
        session.put("input_audio_transcription", Map.of("model", "whisper-1"));

        // Turn detection: server-side VAD
        session.put("turn_detection", Map.of(
                "type", "server_vad",
                "threshold", realtimeProps.getVadThreshold(),
                "prefix_padding_ms", realtimeProps.getVadPrefixPaddingMs(),
                "silence_duration_ms", realtimeProps.getVadSilenceDurationMs()
        ));

        // Tools
        session.put("tools", buildToolDefinitions());
        session.put("tool_choice", "auto");

        // Modalities: text + audio
        session.put("modalities", List.of("text", "audio"));

        return session;
    }

    /**
     * Build system instructions, mirroring LlmFlowService.buildContext().
     */
    private String buildSystemInstructions(Long tenantId, String fromNumber, boolean isReminderCall) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        Map<String, String> vars = buildTemplateVariables(tenantId);

        StringBuilder ctx = new StringBuilder();
        if (isReminderCall) {
            ctx.append("REMINDER CALL MODE: This is an outbound reminder call. You already delivered the reminder. ")
                    .append("Do NOT proactively ask if they want to reschedule or cancel. ")
                    .append("If the user has no questions or says no thank you, say a brief goodbye and end the call. ")
                    .append("If the user explicitly asks to reschedule or cancel, help them with that for this specific appointment.\n\n");
        }
        ctx.append("TODAY'S DATE: ").append(today).append(". Use for \"today\", \"tomorrow\", etc.\n\n");

        // Persona
        String persona = promptService.renderTemplate(tenantId, "system_persona",
                "You are a friendly virtual receptionist. Short, warm, conversational.", vars);
        ctx.append(persona).append("\n\n");

        // Dynamic data
        ctx.append("DATABASE STATE (single source of truth; slots from appointment_slot WHERE status=AVAILABLE):\n\n");

        List<Doctor> doctors = appointmentService.getAllDoctors(tenantId);
        ctx.append("DOCTORS:\n");
        for (Doctor d : doctors) {
            ctx.append("- key: ").append(d.getKey()).append(", name: ").append(d.getName());
            if (StringUtils.isNotBlank(d.getSpecialization())) {
                ctx.append(", specialization: ").append(d.getSpecialization());
            }
            ctx.append("\n");
        }

        Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek(tenantId);
        ctx.append("\nAVAILABLE SLOTS (dynamic; never invent):\n");
        slots.forEach((docKey, byDate) -> {
            ctx.append(docKey).append(": ");
            List<String> parts = new ArrayList<>();
            byDate.forEach((date, times) -> {
                if (times != null && !times.isEmpty()) {
                    parts.add(date + " " + SlotFormattingUtil.formatSlotsAsList(times));
                }
            });
            ctx.append(String.join("; ", parts)).append("\n");
        });

        String resolvedPhone = callerPhoneResolver.resolve(fromNumber);
        List<AppointmentService.AppointmentSummary> appointments =
                StringUtils.isNotBlank(resolvedPhone)
                        ? appointmentService.getUpcomingAppointmentSummaries(resolvedPhone, tenantId)
                        : List.of();
        if (!appointments.isEmpty()) {
            ctx.append("\nCALLER'S UPCOMING APPOINTMENTS: ");
            for (int i = 0; i < appointments.size(); i++) {
                AppointmentService.AppointmentSummary a = appointments.get(i);
                if (i > 0) ctx.append(" | ");
                ctx.append(a.patientName).append(" with ").append(a.doctorName)
                        .append(" on ").append(a.slotDate).append(" at ").append(a.startTime);
            }
            ctx.append("\n");
        } else {
            ctx.append("\nCALLER HAS NO UPCOMING APPOINTMENTS.\n");
        }

        // Rules
        String rules = promptService.renderTemplate(tenantId, "system_rules",
                "- Always confirm before executing any action.\n- Never invent data.", vars);
        ctx.append("\n").append(rules).append("\n");

        // Flows
        String flows = promptService.renderTemplate(tenantId, "system_flows", "", vars);
        if (!flows.isBlank()) {
            ctx.append("\n").append(flows).append("\n");
        }

        // Realtime-specific instructions
        ctx.append("\nIMPORTANT: You are speaking on a phone call. Keep responses concise and natural. ");
        ctx.append("Use the provided tools to book, cancel, or reschedule appointments. ");
        ctx.append("Always confirm details with the caller before executing a tool call.\n");

        return ctx.toString();
    }

    private Map<String, String> buildTemplateVariables(Long tenantId) {
        Map<String, String> vars = new HashMap<>();
        if (tenantId != null) {
            String businessName = tenantService.getTenantName(tenantId);
            vars.put("business_name", businessName != null ? businessName : "our office");
            vars.put("ai_name", tenantService.getConfig(tenantId, "ai_name", "Sarah"));
            vars.put("supported_actions", tenantService.getConfig(tenantId, "supported_actions",
                    "book, reschedule, or cancel appointments"));
            vars.put("business_hours", tenantService.getConfig(tenantId, "business_hours", ""));
            vars.put("business_address", tenantService.getConfig(tenantId, "business_address", ""));
        } else {
            vars.put("business_name", "our office");
            vars.put("ai_name", "Sarah");
            vars.put("supported_actions", "book, reschedule, or cancel appointments");
            vars.put("business_hours", "");
            vars.put("business_address", "");
        }
        return vars;
    }

    /**
     * Build OpenAI Realtime API tool definitions for appointment management.
     * These mirror the operations in AppointmentService.
     */
    private List<Map<String, Object>> buildToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // book_appointment
        tools.add(Map.of(
                "type", "function",
                "name", "book_appointment",
                "description", "Book a new appointment for a patient with a specific doctor, date, and time. " +
                        "Only call this after the caller has confirmed the details.",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "patient_name", Map.of("type", "string", "description", "Patient's full name"),
                                "patient_phone", Map.of("type", "string", "description", "Patient's phone number (optional, defaults to caller's number)"),
                                "doctor_key", Map.of("type", "string", "description", "Doctor key from DOCTORS list (e.g. 'dr_smith')"),
                                "date", Map.of("type", "string", "description", "Appointment date in YYYY-MM-DD format"),
                                "time", Map.of("type", "string", "description", "Appointment time in 12-hour format (e.g. '07:00 PM')")
                        ),
                        "required", List.of("patient_name", "doctor_key", "date", "time")
                )
        ));

        // cancel_appointment
        tools.add(Map.of(
                "type", "function",
                "name", "cancel_appointment",
                "description", "Cancel the caller's upcoming appointment. " +
                        "Only call this after the caller has confirmed they want to cancel.",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "patient_name", Map.of("type", "string", "description", "Patient's name (optional, for disambiguation when multiple appointments exist)")
                        ),
                        "required", List.of()
                )
        ));

        // reschedule_appointment
        tools.add(Map.of(
                "type", "function",
                "name", "reschedule_appointment",
                "description", "Reschedule the caller's upcoming appointment to a new doctor, date, and time. " +
                        "Only call this after the caller has confirmed the new details.",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "patient_name", Map.of("type", "string", "description", "Patient's name (optional, for disambiguation)"),
                                "doctor_key", Map.of("type", "string", "description", "New doctor key from DOCTORS list"),
                                "new_date", Map.of("type", "string", "description", "New appointment date in YYYY-MM-DD format"),
                                "new_time", Map.of("type", "string", "description", "New appointment time in 12-hour format (e.g. '07:00 PM')")
                        ),
                        "required", List.of("doctor_key", "new_date", "new_time")
                )
        ));

        return tools;
    }
}
