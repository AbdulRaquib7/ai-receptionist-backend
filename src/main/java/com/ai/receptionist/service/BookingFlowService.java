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
        public boolean pendingNeedNamePhone;
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

        // Provide name/phone when asked
        if (state != null && state.pendingNeedNamePhone) {
            ExtractedIntent ext = extractIntent(userText, conversationSummary, openAiKey, openAiModel);
            if (StringUtils.isNotBlank(ext.patientName) || StringUtils.isNotBlank(ext.patientPhone)) {
                if (StringUtils.isNotBlank(ext.patientName)) state.patientName = ext.patientName;
                if (StringUtils.isNotBlank(ext.patientPhone)) state.patientPhone = ext.patientPhone;
                if (StringUtils.isNotBlank(state.patientName) && StringUtils.isNotBlank(state.patientPhone)) {
                    state.pendingNeedNamePhone = false;
                    state.pendingConfirmBook = true;
                    List<Doctor> doctors = appointmentService.getAllDoctors();
                    String docName = doctors.stream().filter(d -> d.getKey().equals(state.doctorKey)).findFirst().map(d -> d.getName()).orElse(state.doctorKey);
                    return Optional.of("Your appointment with " + docName + " on " + state.date + " at " + state.time + ". Say yes to confirm.");
                }
            }
            return Optional.of("May I have your name and phone number for the appointment?");
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
            Optional<AppointmentService.AppointmentSummary> existing = appointmentService.getActiveAppointmentSummary(fromNumber);
            if (existing.isEmpty()) {
                return Optional.of("You don't have any active appointment to cancel.");
            }
            PendingState s = getOrCreate(callSid);
            s.pendingConfirmCancel = true;
            s.pendingConfirmBook = false;
            s.pendingConfirmReschedule = false;
            AppointmentService.AppointmentSummary a = existing.get();
            return Optional.of("You have an appointment with " + a.doctorName + " on " +
                    a.slotDate + " at " + a.startTime + ". Say yes to cancel it.");
        }

        if (extracted.intent.equals("reschedule")) {
            Optional<AppointmentService.AppointmentSummary> existingSummary = appointmentService.getActiveAppointmentSummary(fromNumber);
            if (existingSummary.isEmpty()) {
                return Optional.of("You don't have any appointment to reschedule.");
            }
            if (StringUtils.isNotBlank(extracted.doctorKey) && StringUtils.isNotBlank(extracted.date) && StringUtils.isNotBlank(extracted.time)) {
                String date = normalizeDate(extracted.date);
                if (date == null) return Optional.empty();
                String time = normalizeTimeForSlot(extracted.time);
                Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
                List<String> byDate = slots.containsKey(extracted.doctorKey) ? slots.get(extracted.doctorKey).get(date) : null;
                String matchedTime = matchSlotTime(byDate, time);
                if (matchedTime == null) {
                    return Optional.of("That slot is not available. Please choose another date and time.");
                }
                PendingState s = getOrCreate(callSid);
                s.pendingConfirmReschedule = true;
                s.pendingConfirmBook = false;
                s.pendingConfirmCancel = false;
                s.rescheduleDoctorKey = extracted.doctorKey;
                s.rescheduleDate = date;
                s.rescheduleTime = matchedTime;
                List<Doctor> doctors = appointmentService.getAllDoctors();
                String docName = doctors.stream().filter(d -> d.getKey().equals(extracted.doctorKey)).findFirst().map(d -> d.getName()).orElse(extracted.doctorKey);
                return Optional.of("You want to reschedule to " + docName + " on " + date + " at " + matchedTime + ". Say yes to confirm.");
            }
            AppointmentService.AppointmentSummary a = existingSummary.get();
            return Optional.of("Your current appointment is with " + a.doctorName + " on " +
                    a.slotDate + " at " + a.startTime + ". Please say the new doctor name, date, and time you want.");
        }

        if (extracted.intent.equals("book") && StringUtils.isNotBlank(extracted.doctorKey)
                && StringUtils.isNotBlank(extracted.date) && StringUtils.isNotBlank(extracted.time)) {
            String date = normalizeDate(extracted.date);
            if (date == null) return Optional.empty();
            String time = normalizeTimeForSlot(extracted.time);

            Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
            if (!slots.containsKey(extracted.doctorKey)) {
                return Optional.of("Doctor not found. We have Dr Ahmed, Dr John, and Dr Evening.");
            }
            List<String> byDate = slots.get(extracted.doctorKey).get(date);
            String matchedTime = matchSlotTime(byDate, time);
            if (matchedTime == null) {
                return Optional.of("That slot is not available. Please choose another time. Available: " + (byDate != null ? String.join(", ", byDate) : "none"));
            }

            PendingState s = getOrCreate(callSid);
            s.doctorKey = extracted.doctorKey;
            s.date = date;
            s.time = matchedTime;
            s.patientName = extracted.patientName;
            s.patientPhone = extracted.patientPhone;
            s.pendingConfirmCancel = false;
            s.pendingConfirmReschedule = false;

            if (StringUtils.isNotBlank(s.patientName) && StringUtils.isNotBlank(s.patientPhone)) {
                s.pendingConfirmBook = true;
                s.pendingNeedNamePhone = false;
                List<Doctor> doctors = appointmentService.getAllDoctors();
                String docName = doctors.stream().filter(d -> d.getKey().equals(extracted.doctorKey)).findFirst().map(d -> d.getName()).orElse(extracted.doctorKey);
                return Optional.of("Your appointment with " + docName + " on " + date + " at " + matchedTime + ". Say yes to confirm.");
            } else {
                s.pendingNeedNamePhone = true;
                s.pendingConfirmBook = false;
                return Optional.of("May I have your name and phone number for the appointment?");
            }
        }

        // Partial booking - accumulate (user said "Dr Ahmed" or "tomorrow 10am" etc.)
        if (extracted.intent.equals("book")) {
            PendingState s = getOrCreate(callSid);
            if (StringUtils.isNotBlank(extracted.doctorKey)) s.doctorKey = extracted.doctorKey;
            if (StringUtils.isNotBlank(extracted.date)) s.date = normalizeDate(extracted.date);
            if (StringUtils.isNotBlank(extracted.time)) s.time = normalizeTimeForSlot(extracted.time);
            if (StringUtils.isNotBlank(extracted.patientName)) s.patientName = extracted.patientName;
            if (StringUtils.isNotBlank(extracted.patientPhone)) s.patientPhone = extracted.patientPhone;

            if (StringUtils.isNotBlank(s.doctorKey) && s.date != null && StringUtils.isNotBlank(s.time)) {
                Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
                if (slots.containsKey(s.doctorKey)) {
                    List<String> byDate = slots.get(s.doctorKey).get(s.date);
                    String matchedTime = matchSlotTime(byDate, s.time);
                    if (matchedTime != null) {
                        s.time = matchedTime;
                        s.pendingConfirmCancel = false;
                        s.pendingConfirmReschedule = false;
                        if (StringUtils.isNotBlank(s.patientName) && StringUtils.isNotBlank(s.patientPhone)) {
                            s.pendingConfirmBook = true;
                            s.pendingNeedNamePhone = false;
                        } else {
                            s.pendingNeedNamePhone = true;
                            s.pendingConfirmBook = false;
                        }
                        List<Doctor> doctors = appointmentService.getAllDoctors();
                        String docName = doctors.stream().filter(d -> d.getKey().equals(s.doctorKey)).findFirst().map(d -> d.getName()).orElse(s.doctorKey);
                        if (s.pendingConfirmBook) {
                            return Optional.of("Your appointment with " + docName + " on " + s.date + " at " + matchedTime + ". Say yes to confirm.");
                        } else {
                            return Optional.of("May I have your name and phone number for the appointment?");
                        }
                    }
                }
                return Optional.of("That slot is not available. Please choose another time.");
            }
        }

        return Optional.empty();
    }

    private Optional<String> confirmBook(String callSid, String fromNumber, PendingState state) {
        if (StringUtils.isBlank(state.doctorKey) || state.date == null || StringUtils.isBlank(state.time)) {
            clearPending(callSid);
            return Optional.of("I don't have the full booking details. Let's try again.");
        }
        String twilioPhone = StringUtils.isNotBlank(fromNumber) ? fromNumber : state.patientPhone;
        Optional<Appointment> result = appointmentService.bookAppointment(twilioPhone, state.patientName, state.patientPhone,
                state.doctorKey, state.date, state.time);
        clearPending(callSid);
        if (result.isPresent()) {
            log.info("Booked appointment for callSid={} doctor={} date={} time={}", callSid, state.doctorKey, state.date, state.time);
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
        return s.contains("yes") || s.contains("yeah") || s.contains("yep") || s.contains("correct")
                || s.contains("ok") || s.contains("sure") || s.contains("confirm");
    }

    private boolean isNegative(String s) {
        return s.matches("no|nope|cancel|never mind|don't|dont") || (s.length() < 20 && s.contains("no"));
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

    /** Normalize user time like "10.30", "2pm", "10:30 to 11:30" to a canonical form for matching. */
    private String normalizeTimeForSlot(String t) {
        if (t == null) return null;
        t = t.trim();
        if (t.contains(" to ")) t = t.substring(0, t.indexOf(" to ")).trim();
        t = t.replace('.', ':');
        if (t.matches("\\d{1,2}:\\d{2}\\s*(AM|PM)")) return t;
        if (t.matches("\\d{1,2}\\.\\d{2}\\s*(AM|PM)")) return t.replace('.', ':');
        if (t.matches("\\d{1,2}:\\d{2}")) {
            int h = Integer.parseInt(t.split(":")[0]);
            String m = t.split(":")[1];
            return (h >= 12 ? String.format("%d:%s PM", h == 12 ? 12 : h - 12, m) : String.format("%d:%s AM", h == 0 ? 12 : h, m));
        }
        if (t.matches("\\d{1,2}\\s*(AM|PM)")) return t.replaceFirst("(\\d{1,2})\\s*(AM|PM)", "$1:00 $2");
        if (t.toLowerCase().matches("\\d{1,2}\\s*pm")) return t.replaceAll("(?i)pm", "PM").replace(" ", "") + ":00";
        if (t.toLowerCase().matches("\\d{1,2}\\s*am")) return t.replaceAll("(?i)am", "AM").replace(" ", "") + ":00";
        if (t.matches("\\d{1,2}\\.\\d{2}")) {
            int h = Integer.parseInt(t.split("\\.")[0]);
            String m = t.split("\\.")[1];
            return (h >= 12 ? String.format("%d:%s PM", h == 12 ? 12 : h - 12, m) : String.format("%d:%s AM", h == 0 ? 12 : h, m));
        }
        return t;
    }

    /** Find matching slot from available list. Handles "10:30 AM" vs "10.30" etc. */
    private String matchSlotTime(List<String> available, String userTime) {
        if (available == null) return null;
        String norm = normalizeTimeForSlot(userTime);
        if (norm == null) return null;
        for (String s : available) {
            if (s.equalsIgnoreCase(norm)) return s;
            if (normalizeTimeForSlot(s).equalsIgnoreCase(norm)) return s;
        }
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

        String prompt = "Extract from conversation. Output JSON only.\n" +
                "intent: book|cancel|reschedule|none\n" +
                "doctorKey: dr-ahmed|dr-john|dr-evening|null (for Ahmed/John/Evening)\n" +
                "date: YYYY-MM-DD or tomorrow or today\n" +
                "time: 10:30 AM, 12:00 PM, 01:00 PM etc. (use start of range if '10.30 to 11.30')\n" +
                "patientName, patientPhone: from user if given\n\n" +
                "Conversation:\n" + context + "\n\nLast user: " + userText + "\n\nJSON:";

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
