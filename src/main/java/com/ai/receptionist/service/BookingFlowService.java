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
        public boolean pendingRescheduleDetails;  // waiting for user to give new slot
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

        // Reschedule: user previously asked to reschedule, now providing new slot (date/time/doctor)
        if (state != null && state.pendingRescheduleDetails) {
            Optional<AppointmentService.AppointmentSummary> existingSummary = appointmentService.getActiveAppointmentSummary(fromNumber);
            if (existingSummary.isEmpty()) {
                state.pendingRescheduleDetails = false;
                return Optional.of("You don't have any appointment to reschedule.");
            }
            AppointmentService.AppointmentSummary current = existingSummary.get();
            final String resolvedDoctorKey;
            if (StringUtils.isNotBlank(extracted.doctorKey)) {
                resolvedDoctorKey = normalizeDoctorKey(extracted.doctorKey);
            } else {
                resolvedDoctorKey = appointmentService.getAllDoctors().stream()
                        .filter(d -> d.getName().equals(current.doctorName))
                        .findFirst()
                        .map(Doctor::getKey)
                        .orElse(null);
            }
            String date = StringUtils.isNotBlank(extracted.date) ? normalizeDate(extracted.date) : null;
            String time = StringUtils.isNotBlank(extracted.time) ? normalizeTimeForSlot(extracted.time) : null;
            if (resolvedDoctorKey != null && date != null && time != null) {
                Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
                List<String> byDate = slots.containsKey(resolvedDoctorKey) ? slots.get(resolvedDoctorKey).get(date) : null;
                String matchedTime = matchSlotTime(byDate, time);
                if (matchedTime != null) {
                    state.pendingRescheduleDetails = false;
                    state.pendingConfirmReschedule = true;
                    state.rescheduleDoctorKey = resolvedDoctorKey;
                    state.rescheduleDate = date;
                    state.rescheduleTime = matchedTime;
                    List<Doctor> doctors = appointmentService.getAllDoctors();
                    String docName = doctors.stream().filter(d -> d.getKey().equals(resolvedDoctorKey)).findFirst().map(Doctor::getName).orElse(resolvedDoctorKey);
                    return Optional.of("You want to reschedule to " + docName + " on " + date + " at " + matchedTime + ". Say yes to confirm.");
                }
            }
        }

        if (extracted.intent.equals("reschedule")) {
            Optional<AppointmentService.AppointmentSummary> existingSummary = appointmentService.getActiveAppointmentSummary(fromNumber);
            if (existingSummary.isEmpty()) {
                return Optional.of("You don't have any appointment to reschedule.");
            }
            if (StringUtils.isNotBlank(extracted.doctorKey) && StringUtils.isNotBlank(extracted.date) && StringUtils.isNotBlank(extracted.time)) {
                String doctorKey = normalizeDoctorKey(extracted.doctorKey);
                if (doctorKey == null) {
                    return Optional.of(buildDoctorNotFoundMessage());
                }
                String date = normalizeDate(extracted.date);
                if (date == null) return Optional.empty();
                String time = normalizeTimeForSlot(extracted.time);
                Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
                List<String> byDate = slots.containsKey(doctorKey) ? slots.get(doctorKey).get(date) : null;
                String matchedTime = matchSlotTime(byDate, time);
                if (matchedTime == null) {
                    return Optional.of("That slot is not available. Please choose another date and time.");
                }
                PendingState s = getOrCreate(callSid);
                s.pendingConfirmReschedule = true;
                s.pendingConfirmBook = false;
                s.pendingConfirmCancel = false;
                s.rescheduleDoctorKey = doctorKey;
                s.rescheduleDate = date;
                s.rescheduleTime = matchedTime;
                List<Doctor> doctors = appointmentService.getAllDoctors();
                String docName = doctors.stream().filter(d -> d.getKey().equals(doctorKey)).findFirst().map(d -> d.getName()).orElse(doctorKey);
                return Optional.of("You want to reschedule to " + docName + " on " + date + " at " + matchedTime + ". Say yes to confirm.");
            }
            PendingState s = getOrCreate(callSid);
            s.pendingRescheduleDetails = true;
            s.pendingConfirmReschedule = false;
            AppointmentService.AppointmentSummary a = existingSummary.get();
            return Optional.of("Your current appointment is with " + a.doctorName + " on " +
                    a.slotDate + " at " + a.startTime + ". Please say the new date and time you want.");
        }

        if (extracted.intent.equals("book") && StringUtils.isNotBlank(extracted.doctorKey)
                && StringUtils.isNotBlank(extracted.date) && StringUtils.isNotBlank(extracted.time)) {
            String doctorKey = normalizeDoctorKey(extracted.doctorKey);
            if (doctorKey == null) {
                return Optional.of(buildDoctorNotFoundMessage());
            }
            String date = normalizeDate(extracted.date);
            if (date == null) return Optional.empty();
            String time = normalizeTimeForSlot(extracted.time);

            Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
            if (!slots.containsKey(doctorKey)) {
                return Optional.of(buildDoctorNotFoundMessage());
            }
            List<String> byDate = slots.get(doctorKey).get(date);
            String matchedTime = matchSlotTime(byDate, time);
            if (matchedTime == null) {
                PendingState pending = getOrCreate(callSid);
                pending.doctorKey = doctorKey;
                pending.date = date;
                return Optional.of("That slot is not available. Please choose another time.");
            }

            PendingState s = getOrCreate(callSid);
            s.doctorKey = doctorKey;
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
                String docName = doctors.stream().filter(d -> d.getKey().equals(doctorKey)).findFirst().map(d -> d.getName()).orElse(doctorKey);
                return Optional.of("Your appointment with " + docName + " on " + date + " at " + matchedTime + ". Say yes to confirm.");
            } else {
                s.pendingNeedNamePhone = true;
                s.pendingConfirmBook = false;
                return Optional.of("May I have your name and phone number for the appointment?");
            }
        }

        // Partial booking - accumulate (user said doctor name or "tomorrow 10am" etc.)
        if (extracted.intent.equals("book")) {
            PendingState s = getOrCreate(callSid);
            if (StringUtils.isNotBlank(extracted.doctorKey)) {
                String nk = normalizeDoctorKey(extracted.doctorKey);
                if (nk != null) s.doctorKey = nk;
            }
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
        if (d.equalsIgnoreCase("day after tomorrow") || d.equalsIgnoreCase("day after")) return LocalDate.now().plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE);
        return null;
    }

    /** Map user input (name, key, specialization) to actual DB doctor key. Never hardcode; always fetch from DB. */
    private String normalizeDoctorKey(String userInput) {
        if (userInput == null || userInput.isBlank()) return null;
        String k = userInput.trim().toLowerCase();
        List<Doctor> doctors = appointmentService.getAllDoctors();
        for (Doctor d : doctors) {
            if (d.getKey() != null && d.getKey().toLowerCase().equals(k)) return d.getKey();
            if (d.getName() != null && (d.getName().toLowerCase().equals(k) || d.getName().toLowerCase().contains(k))) return d.getKey();
            if (d.getSpecialization() != null && !d.getSpecialization().isBlank()
                    && d.getSpecialization().toLowerCase().contains(k)) return d.getKey();
        }
        return null;
    }

    private String buildDoctorNotFoundMessage() {
        List<Doctor> doctors = appointmentService.getAllDoctors();
        if (doctors.isEmpty()) return "We don't have any doctors available at the moment.";
        String names = doctors.stream().map(Doctor::getName).filter(n -> n != null && !n.isBlank()).reduce((a, b) -> a + ", " + b).orElse("");
        return "Doctor not found. We have " + names + ".";
    }

    /** Normalize user time like "10.30", "2pm", "12 p.m.", "10:30 to 11:30" to canonical "HH:MM AM/PM" for slot matching. */
    private String normalizeTimeForSlot(String t) {
        if (t == null || t.isBlank()) return null;
        t = t.trim();
        if (t.contains(" to ")) t = t.substring(0, t.indexOf(" to ")).trim();

        // Handle "12 p.m.", "12pm", "12 pm" etc. BEFORE dot replacement (dot would corrupt p.m.)
        if (t.toLowerCase().matches("\\d{1,2}\\s*p\\.?m\\.?")) {
            String digits = t.replaceAll("\\D", "");
            int h = digits.isEmpty() ? 12 : Integer.parseInt(digits);
            int hour = (h == 12 || h == 0) ? 12 : (h % 12);
            return String.format("%02d:00 PM", hour == 12 ? 12 : hour);
        }
        if (t.toLowerCase().matches("\\d{1,2}\\s*a\\.?m\\.?")) {
            String digits = t.replaceAll("\\D", "");
            int h = digits.isEmpty() ? 12 : Integer.parseInt(digits);
            int hour = (h == 12 || h == 0) ? 12 : (h % 12);
            return String.format("%02d:00 AM", hour == 12 ? 12 : hour);
        }

        t = t.replace('.', ':');
        if (t.matches("\\d{1,2}:\\d{2}\\s*(AM|PM)")) return t;
        if (t.matches("\\d{1,2}:\\d{2}")) {
            int h = Integer.parseInt(t.split(":")[0]);
            String m = t.split(":")[1];
            return (h >= 12 ? String.format("%d:%s PM", h == 12 ? 12 : h - 12, m) : String.format("%d:%s AM", h == 0 ? 12 : h, m));
        }
        if (t.matches("\\d{1,2}\\s*(AM|PM)")) return t.replaceFirst("(?i)(\\d{1,2})\\s*(AM|PM)", "$1:00 $2");
        if (t.matches("\\d{1,2}\\.\\d{2}")) {
            int h = Integer.parseInt(t.split("\\.")[0]);
            String m = t.split("\\.")[1];
            return (h >= 12 ? String.format("%d:%s PM", h == 12 ? 12 : h - 12, m) : String.format("%d:%s AM", h == 0 ? 12 : h, m));
        }
        if (t.matches("\\d{1,2}")) {
            int h = Integer.parseInt(t);
            if (h >= 1 && h <= 11) return String.format("%d:00 PM", h);
            if (h == 12) return "12:00 PM";
            if (h == 0) return "12:00 AM";
        }
        return t;
    }

    /** Find matching slot from available list. Handles "10:30 AM" vs "10.30", "12 p.m." vs "12:00 PM" etc. */
    private String matchSlotTime(List<String> available, String userTime) {
        if (available == null) return null;
        String norm = normalizeTimeForSlot(userTime);
        if (norm == null) return null;
        // Normalize to comparable form: "12:00 PM" and "01:00 PM" - allow 1 vs 01
        String normComparable = toComparableTime(norm);
        for (String s : available) {
            if (s == null) continue;
            if (s.equalsIgnoreCase(norm)) return s;
            if (toComparableTime(s).equals(normComparable)) return s;
            String slotNorm = normalizeTimeForSlot(s);
            if (slotNorm != null && toComparableTime(slotNorm).equals(normComparable)) return s;
        }
        return null;
    }

    /** "12:00 PM" -> "12:00pm", "01:00 PM" -> "1:00pm" for flexible comparison */
    private static String toComparableTime(String t) {
        if (t == null) return "";
        t = t.trim().toLowerCase().replace(" ", "");
        if (t.startsWith("0") && t.length() > 1 && Character.isDigit(t.charAt(1))) {
            t = t.replaceFirst("^0+(?=\\d)", "");
        }
        return t;
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

        List<Doctor> doctors = appointmentService.getAllDoctors();
        String doctorList = doctors.stream()
                .map(d -> d.getKey() + " (" + d.getName() + (d.getSpecialization() != null && !d.getSpecialization().isBlank() ? ", " + d.getSpecialization() : "") + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        String todayIso = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String prompt = "Extract from conversation. Output JSON only. Today is " + todayIso + ".\n" +
                "intent: book|cancel|reschedule|none\n" +
                "doctorKey: Map user's doctor name to one of these keys from DB: [" + doctorList + "]. Output the key (e.g. dr-ahmed) or empty if unclear.\n" +
                "date: YYYY-MM-DD. Use today=" + todayIso + ", tomorrow=" + LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE) + ". If AI offered slots for today/tomorrow and user accepts time, use that date.\n" +
                "time: 12:00 PM, 12:30 PM, 01:00 PM etc. For '12 p.m.' use 12:00 PM. For '12pm' use 12:00 PM. For '6 to 7' use 06:00 PM.\n" +
                "patientName, patientPhone: from user if given\n" +
                "If user only says time (e.g. '12 p.m. is fine'), keep doctor AND date from recent context (AI's offered slots).\n" +
                "If user says 'to book tomorrow', output date=tomorrow.\n\n" +
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
