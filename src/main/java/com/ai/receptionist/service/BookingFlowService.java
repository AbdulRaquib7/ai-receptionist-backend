package com.ai.receptionist.service;

import com.ai.receptionist.entity.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
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
            case INTENT_CONFIRMATION -> instructions = "Ask if they would like to book a doctor appointment.";
            case DOCTOR_LIST -> {
                List<Doctor> doctors = slotService.getActiveDoctors();
                doctorListText = doctors.isEmpty()
                        ? "No doctors available."
                        : doctors.stream()
                                .map(d -> "- " + d.getName() + " (" + d.getSpecialization() + ")")
                                .collect(Collectors.joining("\n"));
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
                }
            }
            case USER_DETAILS -> instructions = "Ask for the caller's name and phone number to confirm the booking.";
            case CONFIRMATION -> instructions = "Ask the caller to confirm the booking. Do not assume yes.";
            case GREETING, COMPLETED -> instructions = "Be friendly. If booking intent, guide to doctor selection.";
            default -> {}
        }

        return new LlmService.LlmContext(cs, instructions, doctorListText, slotListText);
    }

    /**
     * Process user utterance, update state, and return AI reply.
     */
    public BookingFlowResult processUtterance(String callSid, String userText, List<ChatMessage> history) {
        CallStateEntity state = callStateService.getOrCreate(callSid);
        String lower = userText.toLowerCase().trim();

        // Intent: booking
        if (state.getState() == ConversationState.GREETING && matchesBookingIntent(lower)) {
            callStateService.transition(callSid, ConversationState.INTENT_CONFIRMATION);
        }

        // Confirm intent -> show doctor list
        if (state.getState() == ConversationState.INTENT_CONFIRMATION && matchesConfirmation(lower)) {
            callStateService.transition(callSid, ConversationState.DOCTOR_LIST);
        }

        // Doctor selection (e.g. "Dr. Smith" or "the first one")
        if (state.getState() == ConversationState.DOCTOR_LIST) {
            Doctor selected = matchDoctor(lower, callSid);
            if (selected != null) {
                callStateService.updateSelectedDoctor(callSid, selected.getId());
            }
        }

        // Slot selection (e.g. "tomorrow at 10" or "the first slot")
        if ((state.getState() == ConversationState.DOCTOR_SELECTED || state.getState() == ConversationState.SLOT_SELECTION)
                && state.getSelectedDoctorId() != null) {
            AppointmentSlot slot = matchSlot(lower, callSid);
            if (slot != null) {
                callStateService.updateSelectedSlot(callSid, slot.getId());
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
                SlotService.BookingResult result = slotService.bookAppointment(
                        updated.getSelectedSlotId(),
                        updated.getPatientName(),
                        updated.getPatientPhone());
                if (result.success()) {
                    callStateService.transition(callSid, ConversationState.COMPLETED);
                    return BookingFlowResult.bookingSuccess(result.slot());
                } else {
                    return BookingFlowResult.bookingConflict(result.message());
                }
            }
            if (matchesRejection(lower)) {
                callStateService.transition(callSid, ConversationState.SLOT_SELECTION);
            }
        }

        LlmService.LlmContext ctx = buildContext(callSid);
        String aiText = llmService.generateReplyWithContext(history, ctx);
        return BookingFlowResult.llmReply(aiText);
    }

    private boolean matchesBookingIntent(String text) {
        return text.contains("book") || text.contains("appointment") || text.contains("schedule")
                || text.contains("see a doctor") || text.contains("doctor appointment");
    }

    private Doctor matchDoctor(String text, String callSid) {
        List<Doctor> doctors = slotService.getActiveDoctors();
        for (Doctor d : doctors) {
            if (text.contains(d.getName().toLowerCase())) return d;
        }
        if (text.contains("first") || text.contains("one")) return doctors.isEmpty() ? null : doctors.get(0);
        return null;
    }

    private AppointmentSlot matchSlot(String text, String callSid) {
        CallStateEntity state = callStateService.getOrCreate(callSid);
        if (state.getSelectedDoctorId() == null) return null;
        List<AppointmentSlot> slots = slotService.getAvailableSlots(state.getSelectedDoctorId());
        if (text.contains("first") || text.contains("one")) return slots.isEmpty() ? null : slots.get(0);
        for (AppointmentSlot s : slots) {
            String dateStr = s.getSlotDate().format(DATE_FMT).toLowerCase();
            String timeStr = s.getStartTime().format(TIME_FMT).toLowerCase();
            if (text.contains(dateStr) || text.contains(timeStr)) return s;
        }
        return null;
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
        return text.matches("(?i)(yes|yeah|sure|ok|okay|confirm|please|correct)");
    }

    private boolean matchesRejection(String text) {
        return text.matches("(?i)(no|nope|cancel|different|other)");
    }

    /**
     * Result of processing: either LLM reply or booking outcome.
     */
    public record BookingFlowResult(String aiText, boolean bookingSuccess, boolean bookingConflict, AppointmentSlot bookedSlot) {
        public static BookingFlowResult llmReply(String text) {
            return new BookingFlowResult(text, false, false, null);
        }
        public static BookingFlowResult bookingSuccess(AppointmentSlot slot) {
            return new BookingFlowResult(null, true, false, slot);
        }
        public static BookingFlowResult bookingConflict(String message) {
            return new BookingFlowResult(message, false, true, null);
        }
    }
}
