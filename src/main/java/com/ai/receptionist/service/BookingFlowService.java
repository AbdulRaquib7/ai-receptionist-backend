package com.ai.receptionist.service;

import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.entity.Doctor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-call booking state and intent extraction. Handles book, cancel, reschedule flows.
 */
@Service
public class BookingFlowService {

    private static final Logger log = LoggerFactory.getLogger(BookingFlowService.class);

    private final AppointmentService appointmentService;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, PendingState> pendingByCall = new ConcurrentHashMap<>();

    public BookingFlowService(AppointmentService appointmentService, RestTemplateBuilder builder) {
        this.appointmentService = appointmentService;
        this.restTemplate = builder.build();
    }

    public static class PendingState {
        public String doctorKey;
        public String date;
        public String time;
        public String patientName;
        public String patientPhone;
        public boolean pendingConfirmBook;
        public boolean pendingConfirmCancel;
        public boolean pendingConfirmReschedule;
        public String rescheduleDoctorKey;
        public String rescheduleDate;
        public String rescheduleTime;
    }

    /**
     * Process user message. If we handle it (confirm, cancel, etc.), returns the reply. Otherwise empty.
     */
    public Optional<String> processUserMessage(String callSid, String fromNumber, String userText,
                                                List<String> conversationSummary, String openAiKey, String openAiModel) {
        if (StringUtils.isBlank(userText)) return Optional.empty();

        String normalized = userText.toLowerCase().trim();
        PendingState state = pendingByCall.get(callSid);

        // Confirm booking
        if (state != null && state.pendingConfirmBook && isAffirmative(normalized)) {
            return confirmBook(callSid, fromNumber, state);
        }

        // Confirm cancel
        if (state != null && state.pendingConfirmCancel && isAffirmative(normalized)) {
            return confirmCancel(callSid, fromNumber);
        }

        // Confirm reschedule
        if (state != null && state.pendingConfirmReschedule && isAffirmative(normalized)) {
            return confirmReschedule(callSid, fromNumber, state);
        }

        // User declined
        if (state != null && (state.pendingConfirmBook || state.pendingConfirmCancel || state.pendingConfirmReschedule)
                && isNegative(normalized)) {
            clearPending(callSid);
            return Optional.of("Okay, no changes made. How else can I help you?");
        }

        // Extract intent and entities
        ExtractedIntent extracted = extractIntent(userText, conversationSummary, openAiKey, openAiModel);

        if (extracted.intent.equals("cancel")) {
            Optional<Appointment> existing = appointmentService.getActiveAppointmentByTwilioPhone(fromNumber);
            if (existing.isEmpty()) {
                return Optional.of("You don't have any active appointment to cancel.");
            }
            PendingState s = getOrCreate(callSid);
            s.pendingConfirmCancel = true;
            s.pendingConfirmBook = false;
            s.pendingConfirmReschedule = false;
            Appointment a = existing.get();
            return Optional.of("You have an appointment with " + a.getDoctor().getName() + " on " +
                    a.getSlot().getSlotDate() + " at " + a.getSlot().getStartTime() + ". Say yes to cancel it.");
        }

        if (extracted.intent.equals("reschedule")) {
            Optional<Appointment> existing = appointmentService.getActiveAppointmentByTwilioPhone(fromNumber);
            if (existing.isEmpty()) {
                return Optional.of("You don't have any appointment to reschedule.");
            }
            if (StringUtils.isNotBlank(extracted.doctorKey) && StringUtils.isNotBlank(extracted.date) && StringUtils.isNotBlank(extracted.time)) {
                String date = normalizeDate(extracted.date);
                if (date == null) return Optional.empty();
                Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
                if (!slots.containsKey(extracted.doctorKey) || slots.get(extracted.doctorKey).get(date) == null
                        || !slots.get(extracted.doctorKey).get(date).contains(extracted.time)) {
                    return Optional.of("That slot is not available. Please choose another date and time.");
                }
                PendingState s = getOrCreate(callSid);
                s.pendingConfirmReschedule = true;
                s.pendingConfirmBook = false;
                s.pendingConfirmCancel = false;
                s.rescheduleDoctorKey = extracted.doctorKey;
                s.rescheduleDate = date;
                s.rescheduleTime = extracted.time;
                List<Doctor> doctors = appointmentService.getAllDoctors();
                String docName = doctors.stream().filter(d -> d.getKey().equals(extracted.doctorKey)).findFirst().map(d -> d.getName()).orElse(extracted.doctorKey);
                return Optional.of("You want to reschedule to " + docName + " on " + date + " at " + extracted.time + ". Say yes to confirm.");
            }
            Appointment a = existing.get();
            return Optional.of("Your current appointment is with " + a.getDoctor().getName() + " on " +
                    a.getSlot().getSlotDate() + " at " + a.getSlot().getStartTime() + ". Please say the new doctor name, date, and time you want.");
        }

        if (extracted.intent.equals("book") && StringUtils.isNotBlank(extracted.doctorKey)
                && StringUtils.isNotBlank(extracted.date) && StringUtils.isNotBlank(extracted.time)) {
            String date = normalizeDate(extracted.date);
            if (date == null) return Optional.empty();

            Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
            if (!slots.containsKey(extracted.doctorKey)) {
                return Optional.of("Doctor not found. We have Dr Ahmed, Dr John, and Dr Evening.");
            }
            List<String> byDate = slots.get(extracted.doctorKey).get(date);
            if (byDate == null || !byDate.contains(extracted.time)) {
                return Optional.of("That slot is not available. Please choose another doctor, date, or time.");
            }

            PendingState s = getOrCreate(callSid);
            s.doctorKey = extracted.doctorKey;
            s.date = date;
            s.time = extracted.time;
            s.patientName = extracted.patientName;
            s.patientPhone = extracted.patientPhone;
            s.pendingConfirmBook = true;
            s.pendingConfirmCancel = false;
            s.pendingConfirmReschedule = false;

            List<Doctor> doctors = appointmentService.getAllDoctors();
            String docName = doctors.stream().filter(d -> d.getKey().equals(extracted.doctorKey)).findFirst().map(d -> d.getName()).orElse(extracted.doctorKey);
            return Optional.of("Your appointment with " + docName + " on " + date + " at " + extracted.time + ". Say yes to confirm.");
        }

        // Partial booking - accumulate (user said "Dr Ahmed" or "tomorrow 10am" etc.)
        if (extracted.intent.equals("book")) {
            PendingState s = getOrCreate(callSid);
            if (StringUtils.isNotBlank(extracted.doctorKey)) s.doctorKey = extracted.doctorKey;
            if (StringUtils.isNotBlank(extracted.date)) s.date = normalizeDate(extracted.date);
            if (StringUtils.isNotBlank(extracted.time)) s.time = extracted.time;
            if (StringUtils.isNotBlank(extracted.patientName)) s.patientName = extracted.patientName;
            if (StringUtils.isNotBlank(extracted.patientPhone)) s.patientPhone = extracted.patientPhone;

            if (StringUtils.isNotBlank(s.doctorKey) && s.date != null && StringUtils.isNotBlank(s.time)) {
                Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
                if (slots.containsKey(s.doctorKey)) {
                    List<String> byDate = slots.get(s.doctorKey).get(s.date);
                    if (byDate != null && byDate.contains(s.time)) {
                        s.pendingConfirmBook = true;
                        s.pendingConfirmCancel = false;
                        s.pendingConfirmReschedule = false;
                        List<Doctor> doctors = appointmentService.getAllDoctors();
                        String docName = doctors.stream().filter(d -> d.getKey().equals(s.doctorKey)).findFirst().map(d -> d.getName()).orElse(s.doctorKey);
                        return Optional.of("Your appointment with " + docName + " on " + s.date + " at " + s.time + ". Say yes to confirm.");
                    }
                }
                return Optional.of("That slot is not available. Please choose another time.");
            }
        }

        return Optional.empty();
    }

    private Optional<String> confirmBook(String callSid, String fromNumber, PendingState state) {
        // We should have stored the booking attempt - but we book on confirm. Let me re-check the flow.
        // Actually in the "book" path above we already book immediately when we have doctor+date+time. So there's no "pending confirm" for book in the current design - we book right away.
        // Let me align with Express: store in pending, ask "say yes to confirm", then on yes do the actual book.
        // So we need: when we have doctor+date+time, DON'T book yet. Set pendingConfirmBook and return "Say yes to confirm".
        // On "yes", THEN call bookAppointment.
        if (StringUtils.isBlank(state.doctorKey) || state.date == null || StringUtils.isBlank(state.time)) {
            clearPending(callSid);
            return Optional.of("I don't have the full booking details. Let's try again.");
        }
        Optional<Appointment> result = appointmentService.bookAppointment(fromNumber, state.patientName, state.patientPhone,
                state.doctorKey, state.date, state.time);
        clearPending(callSid);
        if (result.isPresent()) {
            return Optional.of("Your appointment is confirmed. Thank you. Have a great day!");
        }
        return Optional.of("Sorry, that slot was just taken. Please choose another time.");
    }

    private Optional<String> confirmCancel(String callSid, String fromNumber) {
        boolean ok = appointmentService.cancelAppointment(fromNumber);
        clearPending(callSid);
        if (ok) {
            return Optional.of("Your appointment has been cancelled. Thank you.");
        }
        return Optional.of("Could not cancel. Please try again.");
    }

    private Optional<String> confirmReschedule(String callSid, String fromNumber, PendingState state) {
        if (StringUtils.isBlank(state.rescheduleDoctorKey) || state.rescheduleDate == null || StringUtils.isBlank(state.rescheduleTime)) {
            clearPending(callSid);
            return Optional.of("I don't have the new slot. Let's try again.");
        }
        Optional<Appointment> result = appointmentService.rescheduleAppointment(fromNumber, state.rescheduleDoctorKey, state.rescheduleDate, state.rescheduleTime);
        clearPending(callSid);
        if (result.isPresent()) {
            return Optional.of("Your appointment has been rescheduled. Thank you.");
        }
        return Optional.of("That slot is not available. Please try another time.");
    }

    private boolean isAffirmative(String s) {
        return s.matches("yes|yeah|yep|yup|correct|ok|okay|sure|confirm|confirmed");
    }

    private boolean isNegative(String s) {
        return s.matches("no|nope|cancel|never mind|don't|dont");
    }

    private PendingState getOrCreate(String callSid) {
        return pendingByCall.computeIfAbsent(callSid, k -> new PendingState());
    }

    public void clearPending(String callSid) {
        pendingByCall.remove(callSid);
    }

    public PendingState getPending(String callSid) {
        return pendingByCall.get(callSid);
    }

    private String normalizeDate(String d) {
        if (d == null) return null;
        d = d.trim();
        if (d.matches("\\d{4}-\\d{2}-\\d{2}")) return d;
        if (d.equalsIgnoreCase("today")) return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (d.equalsIgnoreCase("tomorrow")) return LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        return null;
    }

    private ExtractedIntent extractIntent(String userText, List<String> conversationSummary, String openAiKey, String openAiModel) {
        ExtractedIntent out = new ExtractedIntent();
        out.intent = "none";

        if (StringUtils.isBlank(openAiKey)) return out;

        String url = "https://api.openai.com/v1/chat/completions";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openAiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String context = conversationSummary != null && !conversationSummary.isEmpty()
                ? String.join("\n", conversationSummary.subList(Math.max(0, conversationSummary.size() - 6), conversationSummary.size()))
                : "";

        String prompt = "You are an intent extractor. Given the conversation and the LAST user message, extract:\n" +
                "- intent: one of book, cancel, reschedule, none\n" +
                "- doctorKey: if user mentioned a doctor, map to dr-ahmed, dr-john, or dr-evening (null otherwise)\n" +
                "- date: YYYY-MM-DD or 'tomorrow' or 'today' (null if not given)\n" +
                "- time: like '10:00 AM', '09:30 AM' (null if not given)\n" +
                "- patientName: user's name if given\n" +
                "- patientPhone: user's phone if given\n\n" +
                "Conversation:\n" + context + "\n\nLast user message: " + userText + "\n\n" +
                "Respond ONLY with valid JSON: {\"intent\":\"...\",\"doctorKey\":\"...\",\"date\":\"...\",\"time\":\"...\",\"patientName\":\"...\",\"patientPhone\":\"...\"}";

        Map<String, Object> body = new HashMap<>();
        body.put("model", openAiModel != null ? openAiModel : "gpt-4o-mini");
        body.put("temperature", 0);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            JsonNode root = mapper.readTree(resp.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            int start = content.indexOf('{');
            if (start >= 0) {
                JsonNode j = mapper.readTree(content.substring(start));
                out.intent = j.path("intent").asText("none");
                out.doctorKey = nullIfEmpty(j.path("doctorKey").asText(""));
                out.date = nullIfEmpty(j.path("date").asText(""));
                out.time = nullIfEmpty(j.path("time").asText(""));
                out.patientName = nullIfEmpty(j.path("patientName").asText(""));
                out.patientPhone = nullIfEmpty(j.path("patientPhone").asText(""));
            }
        } catch (Exception e) {
            log.warn("Intent extraction failed", e);
        }
        return out;
    }

    private static String nullIfEmpty(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static class ExtractedIntent {
        String intent;
        String doctorKey;
        String date;
        String time;
        String patientName;
        String patientPhone;
    }

    /**
     * When we have full booking info, set pending confirm and return the confirmation prompt.
     */
    public Optional<String> trySetPendingBook(String callSid, String fromNumber, String doctorKey, String date, String time, String name, String phone) {
        String d = normalizeDate(date);
        if (d == null) return Optional.empty();

        Map<String, Map<String, List<String>>> available = appointmentService.getAvailableSlotsForNextWeek();
        if (!available.containsKey(doctorKey)) return Optional.empty();
        List<String> byDate = available.get(doctorKey).get(d);
        if (byDate == null || !byDate.contains(time)) return Optional.empty();

        PendingState s = getOrCreate(callSid);
        s.doctorKey = doctorKey;
        s.date = d;
        s.time = time;
        s.patientName = name;
        s.patientPhone = phone;
        s.pendingConfirmBook = true;
        s.pendingConfirmCancel = false;
        s.pendingConfirmReschedule = false;

        List<Doctor> doctors = appointmentService.getAllDoctors();
        String docName = doctors.stream().filter(doc -> doc.getKey().equals(doctorKey)).findFirst().map(doc -> doc.getName()).orElse(doctorKey);
        return Optional.of("Your appointment with " + docName + " on " + d + " at " + time + ". Say yes to confirm.");
    }
}
