package com.ai.receptionist.service;

import com.ai.receptionist.entity.ChatMessage;
import com.ai.receptionist.dto.ChatMessageDto;
import com.ai.receptionist.entity.*;
import com.ai.receptionist.utils.ConversationState;

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

    public LlmService.LlmContext buildContext(String callSid) {
        CallStateEntity state = callStateService.getOrCreate(callSid);
        ConversationState cs = state.getState();

        String doctorListText = null;
        String slotListText = null;
        String instructions = null;

        switch (cs) {
            case INTENT_CONFIRMATION ->
                    instructions = "Ask if they would like to book a doctor appointment.";

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
                                .map(s -> "- " + s.getSlotDate().format(DATE_FMT)
                                        + " at " + s.getStartTime().format(TIME_FMT))
                                .collect(Collectors.joining("\n"));
                }
            }

            case USER_DETAILS ->
                    instructions = "Ask for the caller's name and phone number.";

            case CONFIRMATION ->
                    instructions = "Ask the caller to confirm the booking. Do not assume yes.";

            default ->
                    instructions = "Be friendly and guide the caller step by step.";
        }

        return new LlmService.LlmContext(cs, instructions, doctorListText, slotListText);
    }

    public BookingFlowResult processUtterance(String callSid, String userText, List<ChatMessage> history) {

        CallStateEntity state = callStateService.getOrCreate(callSid);
        String lower = userText.toLowerCase().trim();

        if (state.getState() == ConversationState.GREETING && matchesBookingIntent(lower)) {
            callStateService.transition(callSid, ConversationState.INTENT_CONFIRMATION);
            state = callStateService.getOrCreate(callSid);
        }

        if (state.getState() == ConversationState.INTENT_CONFIRMATION && matchesConfirmation(lower)) {
            callStateService.transition(callSid, ConversationState.DOCTOR_LIST);
            state = callStateService.getOrCreate(callSid);
        }

        if (state.getState() == ConversationState.DOCTOR_LIST) {
            Doctor selected = matchDoctor(lower);
            if (selected != null) {
                callStateService.updateSelectedDoctor(callSid, selected.getId());
                state = callStateService.getOrCreate(callSid);
            }
        }

        if ((state.getState() == ConversationState.DOCTOR_SELECTED
                || state.getState() == ConversationState.SLOT_SELECTION)
                && state.getSelectedDoctorId() != null) {

            AppointmentSlot slot = matchSlot(lower, state.getSelectedDoctorId());
            if (slot != null) {
                callStateService.updateSelectedSlot(callSid, slot.getId());
                state = callStateService.getOrCreate(callSid);
            }
        }

        if (state.getState() == ConversationState.USER_DETAILS) {
            extractAndSavePatientDetails(callSid, userText, state);
            state = callStateService.getOrCreate(callSid);
        }

        if (state.getState() == ConversationState.CONFIRMATION) {
            if (matchesConfirmation(lower)) {
                SlotService.BookingResult result =
                        slotService.bookAppointment(
                                state.getSelectedSlotId(),
                                state.getPatientName(),
                                state.getPatientPhone());

                if (result.success()) {
                    callStateService.transition(callSid, ConversationState.COMPLETED);
                    return BookingFlowResult.bookingSuccess(result.slot());
                }
                return BookingFlowResult.bookingConflict(result.message());
            }

            if (matchesRejection(lower)) {
                callStateService.transition(callSid, ConversationState.SLOT_SELECTION);
            }
        }

        String aiText = llmService.generateReplyWithContext(history, buildContext(callSid));
        return BookingFlowResult.llmReply(aiText);
    }

    private boolean matchesBookingIntent(String t) {
        return t.contains("book") || t.contains("appointment") || t.contains("schedule");
    }

    private Doctor matchDoctor(String text) {
        return slotService.getActiveDoctors().stream()
                .filter(d -> text.contains(d.getName().toLowerCase()))
                .findFirst()
                .orElse(null);
    }

    private AppointmentSlot matchSlot(String text, Long doctorId) {
        List<AppointmentSlot> slots = slotService.getAvailableSlots(doctorId);
        if (text.contains("first") && !slots.isEmpty()) return slots.get(0);

        for (AppointmentSlot s : slots) {
            if (text.contains(s.getStartTime().format(TIME_FMT).toLowerCase())) {
                return s;
            }
        }
        return null;
    }

    private void extractAndSavePatientDetails(String callSid, String userText, CallStateEntity state) {
        String name = state.getPatientName();
        String phone = state.getPatientPhone();

        if (name == null && userText.matches("^[A-Za-z ]{3,80}$")) {
            name = userText.trim();
        }

        if (phone == null) {
            String digits = userText.replaceAll("\\D", "");
            if (digits.length() >= 10) phone = digits;
        }

        if (name != null || phone != null) {
            callStateService.updatePatientDetails(callSid,
                    name != null ? name : state.getPatientName(),
                    phone != null ? phone : state.getPatientPhone());
        }
    }

    private boolean matchesConfirmation(String t) {
        return t.matches("(?i)(yes|yeah|ok|okay|confirm|sure)");
    }

    private boolean matchesRejection(String t) {
        return t.matches("(?i)(no|cancel|different|other)");
    }

    public record BookingFlowResult(
            String aiText,
            boolean bookingSuccess,
            boolean bookingConflict,
            AppointmentSlot bookedSlot
    ) {
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
