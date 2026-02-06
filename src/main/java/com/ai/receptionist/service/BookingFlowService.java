package com.ai.receptionist.service;

import com.ai.receptionist.entity.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Orchestrates the appointment booking flow: state transitions, context building,
 * intent detection, and booking execution. DB is the source of truth.
 */
@Service
public class BookingFlowService {

    private static final Logger log = LoggerFactory.getLogger(BookingFlowService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a");

    private final CallStateService callStateService;
    private final SlotService slotService;
    private final LlmService llmService;

    public BookingFlowService(CallStateService callStateService,
                              SlotService slotService,
                              LlmService llmService) {
        this.callStateService = callStateService;
        this.slotService = slotService;
        this.llmService = llmService;
    }

    /**
     * Build LLM context from current call state (doctors, slots from DB only).
     */
    public LlmService.LlmContext buildContext(String callSid) {
        CallStateEntity state = callStateService.getOrCreate(callSid);
        ConversationState cs = state.getState();

        String doctorListText = null;
        String slotListText = null;
        String instructions = null;

        switch (cs) {
            case INTENT_CONFIRMATION -> instructions = "Confirm booking intent. Say something like: 'Would you like to book a doctor appointment?' or 'I can help with that. Shall we proceed with booking?'";
            case SPECIALIZATION_ASK -> {
                List<String> specializations = slotService.getActiveDoctors().stream()
                        .map(Doctor::getSpecialization)
                        .distinct()
                        .toList();
                String specList = String.join(", ", specializations);
                doctorListText = specializations.isEmpty() ? null
                        : specializations.stream().map(s -> "- " + s).collect(Collectors.joining("\n"));
                instructions = "CRITICAL: Your ONLY job now is to ask for specialization. Your next response MUST be: 'Which specialization do you need? We have " + specList + ".' Do NOT ask for time or anything else. Only ask for specialization.";
            }
            case DOCTOR_LIST -> {
                List<Doctor> doctors = getDoctorsForContext(state);
                doctorListText = doctors.isEmpty()
                        ? "No doctors available."
                        : doctors.stream()
                                .map(d -> "- " + d.getName() + " (" + d.getSpecialization() + ")")
                                .collect(Collectors.joining("\n"));
                instructions = "List these doctors from the doctor table (isActive=true) and ask which one they prefer. Say 'For " + (state.getSelectedSpecialization() != null ? state.getSelectedSpecialization() : "your selection") + ", we have [names]... Which would you like?'";
            }
            case DOCTOR_SELECTED, SLOT_SELECTION -> {
                Long docId = state.getSelectedDoctorId();
                if (docId != null) {
                    doctorListText = slotService.getDoctor(docId)
                            .map(d -> "Selected: " + d.getName() + " (" + d.getSpecialization() + ")")
                            .orElse(null);
                    List<AppointmentSlot> slots = slotService.getAvailableSlots(docId);
                    slotListText = slots.isEmpty()
                            ? "No available slots."
                            : slots.stream()
                                    .limit(10)
                                    .map(s -> "- " + s.getSlotDate().format(DATE_FMT) + " at " + s.getStartTime().format(TIME_FMT))
                                    .collect(Collectors.joining("\n"));
                    instructions = slots.isEmpty()
                            ? "Apologize and say no slots are available for this doctor in the next 7 days."
                            : "These slots are from appointment_slot table (status=AVAILABLE). Offer them and ask which date and time. Say 'We have openings on...' and list them.";
                }
            }
            case USER_DETAILS -> {
                doctorListText = buildDoctorListForAllStates();
                instructions = buildUserDetailsInstructions(state);
            }
            case CONFIRMATION -> {
                doctorListText = buildDoctorListForAllStates();
                instructions = "Ask the caller to confirm the booking. On yes: appointment is booked, patient saved to patient table, slot status set to BOOKED. Do not assume yes.";
            }
            case CANCEL_CONFIRMATION -> instructions = "Ask the caller to confirm cancellation. Say yes to confirm, no to keep.";
            case RESCHEDULE_SLOT_SELECTION -> {
                Long docId = state.getRescheduleDoctorId() != null ? state.getRescheduleDoctorId() : state.getSelectedDoctorId();
                if (docId != null) {
                    doctorListText = slotService.getDoctor(docId).map(d -> "Selected: " + d.getName()).orElse(null);
                    List<AppointmentSlot> slots = slotService.getAvailableSlots(docId);
                    slotListText = slots.isEmpty() ? "No available slots." : slots.stream().limit(10)
                            .map(s -> "- " + s.getSlotDate().format(DATE_FMT) + " at " + s.getStartTime().format(TIME_FMT))
                            .collect(Collectors.joining("\n"));
                }
                instructions = "Offer available slots for rescheduling. Only use the provided slot list.";
            }
            case RESCHEDULE_CONFIRMATION -> instructions = "Ask to confirm the reschedule. Do not assume yes.";
            case GREETING, COMPLETED -> {
                doctorListText = buildDoctorListForAllStates();
                instructions = "Be friendly. You can help book, cancel, or reschedule appointments. "
                        + (doctorListText != null
                                ? "CRITICAL: When caller asks for doctors, names, or list, you MUST respond with the exact list: " + doctorListText.replace("\n", " | ") + ". Never say you cannot provide the list."
                                : "If asked about doctors, say you'll look them up.");
            }
            default -> {}
        }

        return new LlmService.LlmContext(cs, instructions, doctorListText, slotListText);
    }

    private String buildDoctorListForAllStates() {
        List<Doctor> doctors = slotService.getActiveDoctors();
        return doctors.isEmpty() ? null
                : doctors.stream()
                        .map(d -> d.getName() + " (" + d.getSpecialization() + ")")
                        .collect(Collectors.joining(", "));
    }

    private String buildUserDetailsInstructions(CallStateEntity state) {
        if (state.getCallerPhone() != null && !state.getCallerPhone().isEmpty() && state.getPatientPhone() == null) {
            String formatted = formatPhoneForSpeech(state.getCallerPhone());
            return "The caller is from " + formatted + ". Say: I see you're calling from that number. Should I use it? If yes, use it; if they give another number, use that. Then ask for their name. Name and contact will be saved to patient table.";
        }
        return "Ask for the caller's name and phone number. These will be saved to the patient table to complete the booking.";
    }

    private static String formatPhoneForSpeech(String digits) {
        if (digits == null || digits.length() < 10) return digits;
        if (digits.length() == 10) return digits.replaceFirst("(\\d{3})(\\d{3})(\\d{4})", "$1 $2 $3");
        return digits.replaceFirst("(\\d{1})(\\d{3})(\\d{3})(\\d{4})", "$1 $2 $3 $4");
    }

    /**
     * Process user utterance, update state, and return AI reply.
     */
    public BookingFlowResult processUtterance(String callSid, String userText, List<ChatMessage> history) {
        CallStateEntity state = callStateService.getOrCreate(callSid);
        String lower = userText.toLowerCase().trim();

        // Intent: cancel
        if (state.getState() == ConversationState.GREETING && matchesCancelIntent(lower)) {
            String phone = resolvePatientPhone(state);
            var appt = slotService.findLatestConfirmedByPhone(phone);
            if (appt.isEmpty()) {
                return BookingFlowResult.llmReply("You do not have any active appointment to cancel.");
            }
            callStateService.setPending(callSid, appt.get().getId(), "CANCEL");
            callStateService.transition(callSid, ConversationState.CANCEL_CONFIRMATION);
        }

        // Intent: reschedule
        if (state.getState() == ConversationState.GREETING && matchesRescheduleIntent(lower)) {
            String phone = resolvePatientPhone(state);
            var appt = slotService.findLatestConfirmedByPhone(phone);
            if (appt.isEmpty()) {
                return BookingFlowResult.llmReply("You do not have any appointment to reschedule.");
            }
            callStateService.setPending(callSid, appt.get().getId(), "RESCHEDULE");
            callStateService.setRescheduleSlot(callSid, appt.get().getDoctor().getId(), null);
            callStateService.transition(callSid, ConversationState.RESCHEDULE_SLOT_SELECTION);
        }

        // Cancel confirmation
        if (state.getState() == ConversationState.CANCEL_CONFIRMATION && state.getPendingAppointmentId() != null) {
            if (matchesConfirmation(lower)) {
                var result = slotService.cancelAppointment(state.getPendingAppointmentId());
                callStateService.clearPending(callSid);
                callStateService.transition(callSid, ConversationState.COMPLETED);
                if (result.success()) return BookingFlowResult.cancelSuccessResult();
            }
            if (matchesRejection(lower)) {
                callStateService.clearPending(callSid);
                callStateService.transition(callSid, ConversationState.GREETING);
                return BookingFlowResult.llmReply("Okay, your appointment was not cancelled. How else can I help?");
            }
        }

        // Reschedule slot selection
        if (state.getState() == ConversationState.RESCHEDULE_SLOT_SELECTION && state.getPendingAppointmentId() != null) {
            Long docId = state.getRescheduleDoctorId() != null ? state.getRescheduleDoctorId() : state.getSelectedDoctorId();
            if (docId != null) {
                AppointmentSlot slot = matchSlot(lower, callSid);
                if (slot == null && history != null) slot = matchSlotFromHistory(history, callSid);
                if (slot != null) {
                    callStateService.setRescheduleSlot(callSid, docId, slot.getId());
                    callStateService.transition(callSid, ConversationState.RESCHEDULE_CONFIRMATION);
                }
            }
        }

        // Reschedule confirmation
        if (state.getState() == ConversationState.RESCHEDULE_CONFIRMATION && state.getPendingAppointmentId() != null && state.getRescheduleSlotId() != null) {
            if (matchesConfirmation(lower)) {
                var result = slotService.rescheduleAppointment(state.getPendingAppointmentId(), state.getRescheduleSlotId());
                callStateService.clearPending(callSid);
                callStateService.transition(callSid, ConversationState.COMPLETED);
                if (result.success()) return BookingFlowResult.rescheduleSuccessResult(result.slot());
                return BookingFlowResult.bookingConflict(result.message());
            }
            if (matchesRejection(lower)) {
                callStateService.clearPending(callSid);
                callStateService.transition(callSid, ConversationState.GREETING);
                return BookingFlowResult.llmReply("Okay, your appointment was not changed. How else can I help?");
            }
        }

        // Intent: booking
        if (state.getState() == ConversationState.GREETING && matchesBookingIntent(lower)) {
            callStateService.transition(callSid, ConversationState.INTENT_CONFIRMATION);
        }

        // Confirm intent -> ask specialization
        if (state.getState() == ConversationState.INTENT_CONFIRMATION && matchesConfirmation(lower)) {
            callStateService.transition(callSid, ConversationState.SPECIALIZATION_ASK);
        }

        // Specialization selection (e.g. "Cardiology", "General Practice")
        if (state.getState() == ConversationState.SPECIALIZATION_ASK) {
            String matched = matchSpecialization(lower, callSid);
            if (matched != null) {
                callStateService.setSelectedSpecialization(callSid, matched);
                callStateService.transition(callSid, ConversationState.DOCTOR_LIST);
            }
        }

        // Doctor selection (e.g. "Dr. Smith" or "the first one") - only from doctors matching selected specialization
        if (state.getState() == ConversationState.DOCTOR_LIST) {
            Doctor selected = matchDoctor(lower, callSid, state);
            if (selected != null) {
                callStateService.updateSelectedDoctor(callSid, selected.getId());
            }
        }

        // Slot selection (e.g. "tomorrow at 10" or "the first slot")
        if ((state.getState() == ConversationState.DOCTOR_SELECTED || state.getState() == ConversationState.SLOT_SELECTION || state.getState() == ConversationState.RESCHEDULE_SLOT_SELECTION)
                && (state.getSelectedDoctorId() != null || state.getRescheduleDoctorId() != null)) {
            AppointmentSlot slot = matchSlot(lower, callSid);
            if (slot == null && history != null) {
                slot = matchSlotFromHistory(history, callSid);
            }
            if (slot != null && state.getState() != ConversationState.RESCHEDULE_SLOT_SELECTION) {
                callStateService.updateSelectedSlot(callSid, slot.getId());
            }
        }

        // User details (name, phone) - use caller phone if available and user confirms
        if (state.getState() == ConversationState.USER_DETAILS && state.getCallerPhone() != null && state.getPatientPhone() == null) {
            if (matchesConfirmation(lower)) {
                callStateService.updatePatientDetails(callSid, state.getPatientName(), state.getCallerPhone());
            }
        }

        // User details (name, phone)
        if (state.getState() == ConversationState.USER_DETAILS) {
            extractAndSavePatientDetails(callSid, userText, state);
        }

        // Confirmation (yes/no)
        if (state.getState() == ConversationState.CONFIRMATION) {
            CallStateEntity updated = callStateService.getOrCreate(callSid);
            if (matchesConfirmation(lower)) {
                Long slotId = updated.getSelectedSlotId();
                String patientName = updated.getPatientName();
                String patientPhone = updated.getPatientPhone() != null ? updated.getPatientPhone() : updated.getCallerPhone();
                if (slotId == null || patientName == null || StringUtils.isBlank(patientPhone)) {
                    log.warn("Cannot book: slotId={} patientName={} patientPhone={}", slotId, patientName, patientPhone);
                    if (patientName == null || StringUtils.isBlank(patientPhone)) {
                        callStateService.transition(callSid, ConversationState.USER_DETAILS);
                        return BookingFlowResult.llmReply("I need your name and phone number to complete the booking. Could you please provide them?");
                    }
                } else {
                SlotService.BookingResult result = slotService.bookAppointment(
                        slotId,
                        patientName,
                        patientPhone);
                if (result.success()) {
                    callStateService.transition(callSid, ConversationState.COMPLETED);
                    return BookingFlowResult.bookingSuccess(result.slot());
                } else {
                    callStateService.transition(callSid, ConversationState.SLOT_SELECTION);
                    Long docId = updated.getSelectedDoctorId();
                    List<AppointmentSlot> alternatives = docId != null ? slotService.getAvailableSlots(docId) : List.of();
                    String altList = alternatives.isEmpty() ? ""
                            : " We have these times available: "
                            + alternatives.stream().limit(5)
                                    .map(s -> s.getSlotDate().format(DATE_FMT) + " at " + s.getStartTime().format(TIME_FMT))
                                    .collect(Collectors.joining(", "))
                            + ". Which would you prefer?";
                    return BookingFlowResult.llmReply("That appointment time was just booked by someone else." + altList);
                }
                }
            }
            if (matchesRejection(lower)) {
                callStateService.transition(callSid, ConversationState.SLOT_SELECTION);
            }
        }

        LlmService.LlmContext ctx = buildContext(callSid);
        String aiText = llmService.generateReplyWithContext(history, ctx);
        if (StringUtils.isBlank(aiText)) {
            aiText = getFallbackReply(callSid);
        }
        return BookingFlowResult.llmReply(aiText);
    }

    private String getFallbackReply(String callSid) {
        CallStateEntity state = callStateService.getOrCreate(callSid);
        return switch (state.getState()) {
            case GREETING, COMPLETED -> "How can I help you today?";
            case INTENT_CONFIRMATION -> "Would you like to book a doctor appointment?";
            case DOCTOR_SELECTED -> "Which date and time works for you?";
            case SPECIALIZATION_ASK -> {
                List<String> specs = slotService.getActiveDoctors().stream()
                        .map(Doctor::getSpecialization).distinct().toList();
                yield specs.isEmpty() ? "Which specialization do you need?" : "Which specialization do you need? We have " + String.join(", ", specs) + ".";
            }
            case DOCTOR_LIST -> {
                List<Doctor> docs = getDoctorsForContext(state);
                yield docs.isEmpty() ? "No doctors available." : "We have " + docs.stream().map(d -> d.getName() + " (" + d.getSpecialization() + ")").collect(Collectors.joining(", ")) + ". Which would you like?";
            }
            case SLOT_SELECTION -> "Which date and time works for you?";
            case USER_DETAILS -> "May I have your name and phone number?";
            case CONFIRMATION -> "Can you confirm the booking?";
            default -> "How can I assist you?";
        };
    }

    private boolean matchesBookingIntent(String text) {
        return text.contains("book") || text.contains("appointment") || text.contains("schedule")
                || text.contains("see a doctor") || text.contains("doctor appointment");
    }

    private boolean matchesCancelIntent(String text) {
        return text.contains("cancel") || (text.contains("appointment") && text.contains("cancel"));
    }

    private boolean matchesRescheduleIntent(String text) {
        return text.contains("reschedule") || (text.contains("change") && text.contains("appointment"));
    }

    private List<Doctor> getDoctorsForContext(CallStateEntity state) {
        List<Doctor> all = slotService.getActiveDoctors();
        String spec = state.getSelectedSpecialization();
        if (spec == null || spec.isBlank()) return all;
        return all.stream()
                .filter(d -> d.getSpecialization() != null && d.getSpecialization().equalsIgnoreCase(spec))
                .toList();
    }

    private String matchSpecialization(String text, String callSid) {
        List<Doctor> doctors = slotService.getActiveDoctors();
        for (Doctor d : doctors) {
            String spec = d.getSpecialization();
            if (spec != null && text.contains(spec.toLowerCase())) return spec;
        }
        if (text.contains("cardio") || text.contains("heart")) return "Cardiology";
        if (text.contains("general") || text.contains("gp")) return "General Practice";
        if (text.contains("pediatr") || text.contains("child")) return "Pediatrics";
        return null;
    }

    private Doctor matchDoctor(String text, String callSid, CallStateEntity state) {
        List<Doctor> doctors = getDoctorsForContext(state);
        for (Doctor d : doctors) {
            if (text.contains(d.getName().toLowerCase())) return d;
            if (matchesDoctorFuzzy(text, d)) return d;
        }
        if (text.contains("first") || text.contains("one") || text.contains("first one")) return doctors.isEmpty() ? null : doctors.get(0);
        return null;
    }

    private boolean matchesDoctorFuzzy(String text, Doctor d) {
        String name = d.getName().toLowerCase().replaceAll("dr\\.?\\s*", "");
        String spec = d.getSpecialization().toLowerCase();
        String[] parts = name.split("\\s+");
        if (parts.length >= 2) {
            String firstName = parts[0];
            String lastName = parts[parts.length - 1];
            boolean hasFirst = text.contains(firstName);
            String alt = soundAlike(lastName);
            boolean hasLast = text.contains(lastName) || (alt != null && text.contains(alt));
            if (hasFirst && hasLast) return true;
        }
        if (text.contains("heart") && spec.contains("cardiol")) return true;
        if (text.contains("general") && spec.contains("general")) return true;
        if (text.contains("pediatr") && spec.contains("pediatr")) return true;
        return false;
    }

    private String soundAlike(String name) {
        return switch (name) {
            case "chen" -> "chan";
            case "chan" -> "chen";
            case "johnson" -> "johnsen";
            case "johnsen" -> "johnson";
            case "davis" -> "davies";
            case "davies" -> "davis";
            default -> null;
        };
    }

    private String resolvePatientPhone(CallStateEntity state) {
        if (state.getPatientPhone() != null && !state.getPatientPhone().isEmpty()) return state.getPatientPhone();
        return state.getCallerPhone() != null ? state.getCallerPhone() : "";
    }

    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2})[.:\\s]*(\\d{2})(?:\\s*(?:to|and|-)\\s*(\\d{1,2})[.:\\s]*(\\d{2}))?");

    /**
     * Try to match a slot from recent user messages in history (e.g. user said "10.30 to 11.30" before selecting doctor).
     */
    private AppointmentSlot matchSlotFromHistory(List<ChatMessage> history, String callSid) {
        if (history == null || history.isEmpty()) return null;
        int count = 0;
        for (int i = history.size() - 1; i >= 0 && count < 5; i--) {
            ChatMessage m = history.get(i);
            if ("user".equalsIgnoreCase(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                AppointmentSlot s = matchSlot(m.getContent().toLowerCase().trim(), callSid);
                if (s != null) return s;
                count++;
            }
        }
        return null;
    }

    private AppointmentSlot matchSlot(String text, String callSid) {
        CallStateEntity state = callStateService.getOrCreate(callSid);
        Long docId = state.getSelectedDoctorId() != null ? state.getSelectedDoctorId() : state.getRescheduleDoctorId();
        if (docId == null) return null;
        List<AppointmentSlot> slots = slotService.getAvailableSlots(docId);
        if (slots.isEmpty()) return null;
        if (text.contains("first") || text.contains("one") || text.contains("first one")) return slots.get(0);

        LocalDate today = LocalDate.now();
        List<LocalTime> parsedTimes = parseTimesFromText(text);

        for (AppointmentSlot s : slots) {
            String dateStr = s.getSlotDate().format(DATE_FMT).toLowerCase();
            String timeStr = s.getStartTime().format(TIME_FMT).toLowerCase();
            if (text.contains(dateStr) || text.contains(timeStr)) return s;
            if (text.contains("today") && s.getSlotDate().equals(today)) return s;
            if (text.contains("tomorrow") && s.getSlotDate().equals(today.plusDays(1))) return s;
            for (LocalTime pt : parsedTimes) {
                if (s.getStartTime().equals(pt)) return s;
            }
        }
        return null;
    }

    /**
     * Parse time expressions like "5.30", "5:30", "5 30", "5.30 to 6.30" into 24h LocalTime.
     * Hours 1-7 without AM/PM are assumed PM (e.g. 5.30 -> 17:30).
     */
    private List<LocalTime> parseTimesFromText(String text) {
        List<LocalTime> result = new ArrayList<>();
        Matcher m = TIME_PATTERN.matcher(text);
        while (m.find()) {
            int h1 = Integer.parseInt(m.group(1));
            int min1 = Integer.parseInt(m.group(2));
            LocalTime t1 = to24h(h1, min1);
            if (t1 != null) result.add(t1);
            if (m.group(3) != null && m.group(4) != null) {
                int h2 = Integer.parseInt(m.group(3));
                int min2 = Integer.parseInt(m.group(4));
                LocalTime t2 = to24h(h2, min2);
                if (t2 != null) result.add(t2);
            }
        }
        return result;
    }

    private LocalTime to24h(int hour12, int minute) {
        if (minute < 0 || minute > 59) return null;
        int h24;
        if (hour12 >= 1 && hour12 <= 7) {
            h24 = hour12 + 12;
        } else if (hour12 >= 8 && hour12 <= 12) {
            h24 = hour12 == 12 ? 12 : hour12;
        } else {
            return null;
        }
        try {
            return LocalTime.of(h24, minute);
        } catch (Exception e) {
            return null;
        }
    }

    private void extractAndSavePatientDetails(String callSid, String userText, CallStateEntity state) {
        // Simple extraction: assume "My name is X" or "X" and phone digits
        String name = state.getPatientName();
        String phone = state.getPatientPhone();
        if (name == null && userText.matches("(?i).*my name is\\s+(.+)")) {
            name = userText.replaceFirst("(?i)my name is\\s+", "").trim();
            if (name.length() > 100) name = name.substring(0, 100);
        } else if (name == null && userText.length() > 2 && userText.length() < 80 && !userText.matches(".*\\d{10}.*")) {
            name = userText.trim();
        }
        if (phone == null) {
            String digits = userText.replaceAll("\\D", "");
            if (digits.length() >= 10) phone = digits;
        }
        if (name != null || phone != null) {
            callStateService.updatePatientDetails(callSid, name != null ? name : state.getPatientName(), phone != null ? phone : state.getPatientPhone());
        }
    }

    private boolean matchesConfirmation(String text) {
        if (text.matches("(?i)(yes|yeah|sure|ok|okay|confirm|please|correct)")) return true;
        if (text.trim().equalsIgnoreCase("you") && text.length() <= 5) return true;
        return false;
    }

    private boolean matchesRejection(String text) {
        return text.matches("(?i)(no|nope|cancel|different|other)");
    }

    /**
     * Result of processing: either LLM reply or booking/cancel/reschedule outcome.
     */
    public record BookingFlowResult(String aiText, boolean bookingSuccess, boolean bookingConflict, boolean cancelSuccess, boolean rescheduleSuccess, AppointmentSlot bookedSlot) {
        public static BookingFlowResult llmReply(String text) {
            return new BookingFlowResult(text, false, false, false, false, null);
        }
        public static BookingFlowResult bookingSuccess(AppointmentSlot slot) {
            return new BookingFlowResult(null, true, false, false, false, slot);
        }
        public static BookingFlowResult bookingConflict(String message) {
            return new BookingFlowResult(message, false, true, false, false, null);
        }
        public static BookingFlowResult cancelSuccessResult() {
            return new BookingFlowResult(null, false, false, true, false, null);
        }
        public static BookingFlowResult rescheduleSuccessResult(AppointmentSlot slot) {
            return new BookingFlowResult(null, false, false, false, true, slot);
        }
    }
}
