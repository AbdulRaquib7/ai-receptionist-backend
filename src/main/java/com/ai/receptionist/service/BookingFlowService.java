package com.ai.receptionist.service;

import com.ai.receptionist.conversation.ConversationIntent;
import com.ai.receptionist.conversation.IntentResult;
import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.conversation.ConversationState;
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
    private final YesNoClassifier yesNoClassifier;
    private final ResponsePhrases phrases;
    private final IntentClassifier intentClassifier;
    private final IntentPriorityResolver intentPriorityResolver;

    private final Map<String, PendingState> pendingByCall = new ConcurrentHashMap<>();

    public BookingFlowService(AppointmentService appointmentService, RestTemplateBuilder builder,
                              YesNoClassifier yesNoClassifier, ResponsePhrases phrases,
                              IntentClassifier intentClassifier, IntentPriorityResolver intentPriorityResolver) {
        this.appointmentService = appointmentService;
        this.restTemplate = builder.build();
        this.yesNoClassifier = yesNoClassifier;
        this.phrases = phrases;
        this.intentClassifier = intentClassifier;
        this.intentPriorityResolver = intentPriorityResolver;
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
        public boolean pendingChooseCancelAppointment;   // multiple appts, waiting for user to say which name
        public boolean pendingChooseRescheduleAppointment;
        public boolean pendingConfirmAbort;   // user said no; waiting for "stop" or "start over"
        public String cancelPatientName;
        public String reschedulePatientName;
        public String rescheduleDoctorKey;
        public String rescheduleDate;
        public String rescheduleTime;
        public ConversationState currentState = ConversationState.START;
        /** Locked after booking completes; prevents duplicate confirmation. */
        public boolean bookingLocked;
        /** Same as bookingLocked; idempotent booking guard. */
        public boolean bookingCompleted;

        public boolean hasAnyPending() {
            return pendingConfirmBook || pendingNeedNamePhone || pendingConfirmCancel
                    || pendingConfirmReschedule || pendingRescheduleDetails
                    || pendingChooseCancelAppointment || pendingChooseRescheduleAppointment
                    || pendingConfirmAbort;
        }
    }

    /**
     * Process user message. If we handle it (confirm, cancel, etc.), returns the reply. Otherwise empty.
     */
    public Optional<String> processUserMessage(String callSid, String fromNumber, String userText,
                                                List<String> conversationSummary, String openAiKey, String openAiModel) {
        if (StringUtils.isBlank(userText)) return Optional.empty();

        String normalized = userText.toLowerCase().trim();
        PendingState state = pendingByCall.get(callSid);

        // Strict confirmation gate: multi-intent resolution
        if (state != null && state.pendingConfirmBook && !state.bookingLocked && !state.bookingCompleted) {
            IntentResult intentResult = intentClassifier.classifyMulti(userText, true);
            if (intentPriorityResolver.shouldDeferConfirmationForDoctorInfo(intentResult)) {
                log.info("CONFIRM deferred: ASK_DOCTOR_INFO/CHANGE_DOCTOR overrides CONFIRM_YES | intents={}", intentResult.getIntents());
                return buildDoctorInfoThenConfirmPrompt(state);
            }
            if (!intentPriorityResolver.isBookingAllowed(intentResult)) {
                return Optional.empty();
            }
            if (intentResult.isSingleIntent(ConversationIntent.CONFIRM_YES)) {
                return confirmBook(callSid, fromNumber, state);
            }
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
                    return Optional.of(phrases.confirmBookingPrompt(docName, state.date, state.time));
                }
            }
            return Optional.of(phrases.needNameAndPhone());
        }

        // User specifying which appointment to cancel (when multiple)
        if (state != null && state.pendingChooseCancelAppointment) {
            String name = extractIntent(userText, conversationSummary, openAiKey, openAiModel).patientName;
            if (StringUtils.isNotBlank(name)) {
                Optional<AppointmentService.AppointmentSummary> match = appointmentService.getActiveAppointmentSummary(fromNumber, name);
                if (match.isPresent()) {
                    state.pendingChooseCancelAppointment = false;
                    state.pendingConfirmCancel = true;
                    state.cancelPatientName = name;
                    AppointmentService.AppointmentSummary a = match.get();
                    return Optional.of(phrases.confirmCancelPrompt(a.patientName, a.doctorName, a.slotDate, a.startTime));
                }
            }
        }

        // User specifying which appointment to reschedule (when multiple)
        if (state != null && state.pendingChooseRescheduleAppointment) {
            String name = extractIntent(userText, conversationSummary, openAiKey, openAiModel).patientName;
            if (StringUtils.isNotBlank(name)) {
                Optional<AppointmentService.AppointmentSummary> match = appointmentService.getActiveAppointmentSummary(fromNumber, name);
                if (match.isPresent()) {
                    state.pendingChooseRescheduleAppointment = false;
                    state.pendingRescheduleDetails = true;
                    state.reschedulePatientName = name;
                    AppointmentService.AppointmentSummary a = match.get();
                    return Optional.of("Your appointment for " + a.patientName + " is with " + a.doctorName + " on " + a.slotDate + " at " + a.startTime + ". Please say the new date and time you want.");
                }
            }
        }

        // Confirm cancel
        if (state != null && state.pendingConfirmCancel && yesNoClassifier.isAffirmative(userText)) {
            return confirmCancel(callSid, fromNumber, state.cancelPatientName);
        }

        // Confirm reschedule
        if (state != null && state.pendingConfirmReschedule && yesNoClassifier.isAffirmative(userText)) {
            return confirmReschedule(callSid, fromNumber, state);
        }

        // User said NO during confirm -> ask stop or start over
        if (state != null && (state.pendingConfirmBook || state.pendingConfirmCancel || state.pendingConfirmReschedule)
                && yesNoClassifier.isNegative(userText)) {
            state.pendingConfirmBook = false;
            state.pendingConfirmCancel = false;
            state.pendingConfirmReschedule = false;
            state.pendingConfirmAbort = true;
            state.currentState = ConversationState.CONFIRM_ABORT;
            return Optional.of(phrases.noChangesAbortChoice());
        }

        // Post-booking: user says "no" to "Can I help you with anything else?" -> polite goodbye
        if ((state == null || !state.hasAnyPending()) && (normalized.equals("no") || normalized.equals("nope"))) {
            if (conversationSummary != null && !conversationSummary.isEmpty()) {
                String lastMsg = conversationSummary.get(conversationSummary.size() - 1);
                String lastLower = lastMsg.toLowerCase();
                if (lastLower.contains("assistant:") && (lastLower.contains("anything else") || lastLower.contains("help you with"))) {
                    return Optional.of(phrases.goodbye());
                }
            }
        }

        // CONFIRM_ABORT: user chose stop (end call) or start over
        if (state != null && state.pendingConfirmAbort) {
            if (wantsToEndCall(normalized)) {
                clearPending(callSid);
                return Optional.of(phrases.goodbye());
            }
            if (wantsToStartOver(normalized)) {
                clearPending(callSid);
                return Optional.of(phrases.startOver());
            }
            state.pendingConfirmAbort = false;
        }

        // Extract intent and entities
        ExtractedIntent extracted = extractIntent(userText, conversationSummary, openAiKey, openAiModel);

        // Digits-only: treat as phone, not time
        if (StringUtils.isNotBlank(extracted.time) && isLikelyPhoneNumber(extracted.time)) {
            extracted.time = null;
            if (StringUtils.isBlank(extracted.patientPhone)) extracted.patientPhone = userText.replaceAll("\\D", "");
        }

        if (extracted.intent.equals("check_appointments")) {
            List<AppointmentService.AppointmentSummary> list = appointmentService.getActiveAppointmentSummaries(fromNumber);
            if (list.isEmpty()) return Optional.of("You don't have any appointments right now.");
            if (list.size() == 1) {
                AppointmentService.AppointmentSummary a = list.get(0);
                return Optional.of("Yeah, you've got one — " + a.patientName + " with " + a.doctorName + " on " + a.slotDate + " at " + a.startTime + ".");
            }
            StringBuilder sb = new StringBuilder("You've got " + list.size() + ": ");
            for (int i = 0; i < list.size(); i++) {
                AppointmentService.AppointmentSummary a = list.get(i);
                if (i > 0) sb.append("; ");
                sb.append(a.patientName).append(" with ").append(a.doctorName).append(" on ").append(a.slotDate).append(" at ").append(a.startTime);
            }
            return Optional.of(sb.append(".").toString());
        }

        if (extracted.intent.equals("ask_availability") && intentClassifier.classify(userText, false) != ConversationIntent.ASK_DOCTOR_INFO) {
            Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
            if (slots.isEmpty()) return Optional.of("No slots at the moment.");
            PendingState s = getPending(callSid);
            String doctorKey = null;
            if (s != null && s.pendingRescheduleDetails && StringUtils.isNotBlank(s.reschedulePatientName)) {
                doctorKey = appointmentService.getActiveAppointmentSummary(fromNumber, s.reschedulePatientName)
                        .flatMap(a -> appointmentService.getAllDoctors().stream()
                                .filter(d -> d.getName().equals(a.doctorName))
                                .findFirst()
                                .map(Doctor::getKey))
                        .orElse(null);
            }
            if (doctorKey == null && extracted.doctorKey != null) {
                doctorKey = normalizeDoctorKey(extracted.doctorKey);
            }
            if (doctorKey == null && s != null && StringUtils.isNotBlank(s.doctorKey)) {
                doctorKey = s.doctorKey;
            }
            if (doctorKey == null) {
                return Optional.of("Which doctor would you like me to check slots for?");
            }
            if (!slots.containsKey(doctorKey)) {
                return Optional.of("No slots for that doctor at the moment.");
            }
            final String resolvedDoctorKey = doctorKey;
            PendingState stateForBook = getOrCreate(callSid);
            stateForBook.doctorKey = resolvedDoctorKey;
            List<String> samples = new ArrayList<>();
            slots.get(resolvedDoctorKey).forEach((date, times) -> {
                if (samples.size() < 3 && times != null && !times.isEmpty())
                    samples.add(date + ": " + LlmService.formatSlotsAsRanges(times));
            });
            List<Doctor> doctors = appointmentService.getAllDoctors();
            String docName = doctors.stream().filter(d -> d.getKey().equals(resolvedDoctorKey)).findFirst().map(Doctor::getName).orElse(resolvedDoctorKey);
            return Optional.of("Slots for " + docName + " — " + String.join("; ", samples) + ". Pick a time that works.");
        }

        if (extracted.intent.equals("cancel")) {
            List<AppointmentService.AppointmentSummary> list = appointmentService.getActiveAppointmentSummaries(fromNumber);
            if (list.isEmpty()) {
                return Optional.of(phrases.noAppointmentsToCancel());
            }
            PendingState s = getOrCreate(callSid);
            s.pendingConfirmBook = false;
            s.pendingConfirmReschedule = false;
            if (list.size() == 1) {
                s.pendingConfirmCancel = true;
                s.cancelPatientName = null;
                s.currentState = ConversationState.CANCEL_APPOINTMENT;
                AppointmentService.AppointmentSummary a = list.get(0);
                return Optional.of(phrases.confirmCancelPrompt(a.patientName, a.doctorName, a.slotDate, a.startTime));
            }
            if (StringUtils.isNotBlank(extracted.patientName)) {
                Optional<AppointmentService.AppointmentSummary> match = appointmentService.getActiveAppointmentSummary(fromNumber, extracted.patientName);
                if (match.isPresent()) {
                    s.pendingConfirmCancel = true;
                    s.cancelPatientName = extracted.patientName;
                    AppointmentService.AppointmentSummary a = match.get();
                    return Optional.of(phrases.confirmCancelPrompt(a.patientName, a.doctorName, a.slotDate, a.startTime));
                }
            }
            s.pendingChooseCancelAppointment = true;
            s.pendingConfirmCancel = false;
            String names = list.stream().map(a -> a.patientName + " on " + a.slotDate + " at " + a.startTime)
                    .reduce((x, y) -> x + "; " + y).orElse("");
            return Optional.of("You have " + list.size() + " appointments: " + names + ". Which one do you want to cancel? Say the name.");
        }

        // Reschedule: user previously asked to reschedule, now providing new slot (date/time/doctor)
        if (state != null && state.pendingRescheduleDetails) {
            Optional<AppointmentService.AppointmentSummary> existingSummary = appointmentService.getActiveAppointmentSummary(
                    fromNumber, state.reschedulePatientName);
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
                    return Optional.of(phrases.reschedulePromptNewSlot(docName, date, matchedTime));
                }
            }
        }

        if (extracted.intent.equals("reschedule")) {
            List<AppointmentService.AppointmentSummary> list = appointmentService.getActiveAppointmentSummaries(fromNumber);
            if (list.isEmpty()) {
                return Optional.of(phrases.noAppointmentsToReschedule());
            }
            if (list.size() > 1) {
                if (StringUtils.isNotBlank(extracted.patientName)) {
                    Optional<AppointmentService.AppointmentSummary> match = appointmentService.getActiveAppointmentSummary(fromNumber, extracted.patientName);
                    if (match.isPresent()) {
                        PendingState s = getOrCreate(callSid);
                        s.pendingRescheduleDetails = true;
                        s.reschedulePatientName = extracted.patientName;
                        AppointmentService.AppointmentSummary a = match.get();
                        return Optional.of("Your appointment for " + a.patientName + " is with " + a.doctorName + " on " + a.slotDate + " at " + a.startTime + ". Please say the new date and time you want.");
                    }
                }
                PendingState s = getOrCreate(callSid);
                s.pendingChooseRescheduleAppointment = true;
                s.pendingRescheduleDetails = false;
                String names = list.stream().map(a -> a.patientName + " on " + a.slotDate + " at " + a.startTime)
                        .reduce((x, y) -> x + "; " + y).orElse("");
                return Optional.of("You have " + list.size() + " appointments: " + names + ". Which one do you want to reschedule? Say the name.");
            }
            Optional<AppointmentService.AppointmentSummary> existingSummary = Optional.of(list.get(0));
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
            s.reschedulePatientName = null;
            AppointmentService.AppointmentSummary a = existingSummary.get();
            return Optional.of("Your appointment for " + a.patientName + " is with " + a.doctorName + " on " + a.slotDate + " at " + a.startTime + ". Please say the new date and time you want.");
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
                return Optional.of(phrases.slotUnavailable());
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
                return Optional.of(phrases.confirmBookingPrompt(docName, date, matchedTime));
            } else {
                s.pendingNeedNamePhone = true;
                s.pendingConfirmBook = false;
                return Optional.of(phrases.needNameAndPhone());
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
                            return Optional.of(phrases.needNameAndPhone());
                        }
                    }
                }
                return Optional.of(phrases.slotUnavailable());
            }
        }

        return Optional.empty();
    }

    private Optional<String> confirmBook(String callSid, String fromNumber, PendingState state) {
        if (!state.pendingConfirmBook) {
            throw new IllegalStateException("Booking attempted without confirmation.");
        }
        if (state.bookingLocked) {
            return Optional.of(phrases.bookingConfirmed());
        }
        if (StringUtils.isBlank(state.doctorKey) || state.date == null || StringUtils.isBlank(state.time)) {
            clearPending(callSid);
            return Optional.of("I don't have the full booking details. Let's try again.");
        }
        String twilioPhone = StringUtils.isNotBlank(fromNumber) ? fromNumber : state.patientPhone;
        Optional<Appointment> result = appointmentService.bookAppointment(twilioPhone, state.patientName, state.patientPhone,
                state.doctorKey, state.date, state.time);
        if (result.isPresent()) {
            state.bookingLocked = true;
            state.bookingCompleted = true;
            log.info("Booked appointment for callSid={} doctor={} date={} time={}", callSid, state.doctorKey, state.date, state.time);
            clearPending(callSid);
            return Optional.of(phrases.bookingConfirmed());
        }
        return Optional.of(phrases.slotUnavailable());
    }

    private Optional<String> confirmCancel(String callSid, String fromNumber, String patientName) {
        boolean ok = appointmentService.cancelAppointment(fromNumber, patientName);
        clearPending(callSid);
        if (ok) {
            return Optional.of(phrases.cancelConfirmed());
        }
        return Optional.of("Couldn't cancel that. Want to try again?");
    }

    private Optional<String> confirmReschedule(String callSid, String fromNumber, PendingState state) {
        if (StringUtils.isBlank(state.rescheduleDoctorKey) || state.rescheduleDate == null || StringUtils.isBlank(state.rescheduleTime)) {
            clearPending(callSid);
            return Optional.of("I don't have the new slot. Let's try again.");
        }
        Optional<Appointment> result = appointmentService.rescheduleAppointment(
                fromNumber, state.reschedulePatientName, state.rescheduleDoctorKey, state.rescheduleDate, state.rescheduleTime);
        clearPending(callSid);
        if (result.isPresent()) {
            return Optional.of(phrases.rescheduleConfirmed());
        }
        return Optional.of(phrases.slotUnavailable());
    }

    /** When user asks doctor info during confirmation, return DB-backed doctor details and re-prompt for confirm. */
    private Optional<String> buildDoctorInfoThenConfirmPrompt(PendingState state) {
        if (StringUtils.isBlank(state.doctorKey)) return Optional.empty();
        List<Doctor> doctors = appointmentService.getAllDoctors();
        Doctor doc = doctors.stream().filter(d -> state.doctorKey.equals(d.getKey())).findFirst().orElse(null);
        if (doc == null) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        sb.append(doc.getName()).append(" is our ");
        if (StringUtils.isNotBlank(doc.getSpecialization())) {
            sb.append(doc.getSpecialization().toLowerCase()).append(". ");
        } else {
            sb.append("doctor. ");
        }
        sb.append(phrases.confirmAfterDoctorInfo());
        return Optional.of(sb.toString());
    }

    private boolean wantsToEndCall(String normalized) {
        return normalized.contains("end") || normalized.contains("hang up") || normalized.contains("hangup")
                || normalized.contains("bye") || normalized.contains("goodbye")
                || normalized.contains("that's all") || normalized.contains("nothing else")
                || (normalized.length() <= 5 && (normalized.equals("no") || normalized.equals("bye")));
    }

    private boolean wantsToStartOver(String normalized) {
        return normalized.contains("something else") || normalized.contains("start over") || normalized.contains("continue")
                || normalized.contains("try again") || normalized.contains("yes") || normalized.equals("sure");
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

    /** Map user input (name, key, specialization) to actual DB doctor key. All matching from DB only; intent extractor handles variants (e.g. Allen/Alan). */
    private String normalizeDoctorKey(String userInput) {
        if (userInput == null || userInput.isBlank()) return null;
        String k = userInput.trim().toLowerCase().replace(".", "");
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
                "intent: book|cancel|reschedule|check_appointments|ask_availability|none\n" +
                "check_appointments: 'do I have an appointment', 'what are my appointments', 'any appointment for my number'.\n" +
                "ask_availability: 'what dates/times available', 'available slots', 'list doctors and slots'.\n" +
                "doctorKey: Map user's doctor name to one of these keys from DB: [" + doctorList + "]. Use context to resolve variants (e.g. Allen/Alan, John/Jon). For ask_availability, if AI just recommended a doctor (e.g. 'Dr John... would you like me to check slots?') and user says 'yes/sure/check slots', extract that doctor's key from the AI message. Output the matching key or empty if unclear.\n" +
                "date: YYYY-MM-DD. Use today=" + todayIso + ", tomorrow=" + LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE) + ". If AI offered slots for today/tomorrow and user accepts time, use that date.\n" +
                "time: 12:00 PM, 12:30 PM, 01:00 PM etc. For '12 p.m.' use 12:00 PM. For '12pm' use 12:00 PM. For '6 to 7' use 06:00 PM.\n" +
                "patientName, patientPhone: from user if given. For cancel/reschedule with multiple appointments, extract the name (e.g. 'cancel Selva's' -> patientName=Selva).\n" +
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

    private static boolean isLikelyPhoneNumber(String s) {
        if (s == null || s.isBlank()) return false;
        String digits = s.replaceAll("\\D", "");
        return digits.length() >= 6;
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
        return Optional.of(phrases.confirmBookingPrompt(docName, d, time));
    }
}
