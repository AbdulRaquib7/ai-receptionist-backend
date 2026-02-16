package com.ai.receptionist.service;

import com.ai.receptionist.component.ResponsePhrases;
import com.ai.receptionist.conversation.IntentResult;
import com.ai.receptionist.dto.PendingStateDto;
import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.utils.ConversationIntent;
import com.ai.receptionist.utils.ConversationState;
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

@Service
public class BookingFlowService {

    private static final Logger log = LoggerFactory.getLogger(BookingFlowService.class);

    private final AppointmentService appointmentService;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final YesNoClassifierService yesNoClassifier;
    private final ResponsePhrases phrases;
    private final IntentClassifier intentClassifier;
    private final IntentPriorityResolverService intentPriorityResolver;

    private final Map<String, PendingStateDto> pendingByCall = new ConcurrentHashMap<>();

    public BookingFlowService(AppointmentService appointmentService, RestTemplateBuilder builder,
    		YesNoClassifierService yesNoClassifier, ResponsePhrases phrases,
                              IntentClassifier intentClassifier, IntentPriorityResolverService intentPriorityResolver) {
        this.appointmentService = appointmentService;
        this.restTemplate = builder.build();
        this.yesNoClassifier = yesNoClassifier;
        this.phrases = phrases;
        this.intentClassifier = intentClassifier;
        this.intentPriorityResolver = intentPriorityResolver;
    }
    
    

    public Optional<String> processUserMessage(String callSid, String fromNumber, String userText,
                                                List<String> conversationSummary, String openAiKey, String openAiModel) {
        if (StringUtils.isBlank(userText)) return Optional.empty();

        String normalized = userText.toLowerCase().trim();
        PendingStateDto state = pendingByCall.get(callSid);

        if (wantsToEndCall(normalized)) {
            return Optional.of(phrases.goodbye());
        }

        if (isListDoctorsRequest(normalized)) {
            return buildListDoctorsResponse();
        }

        if (isAbortBookingRequest(normalized) && state != null
                && (state.pendingNeedNamePhone || state.pendingConfirmBook || StringUtils.isNotBlank(state.doctorKey))) {
            clearPending(callSid);
            return Optional.of(phrases.abortBooking());
        }

        if (isAlreadyBookedAcknowledgment(normalized)) {
            List<AppointmentService.AppointmentSummary> appts = appointmentService.getActiveAppointmentSummaries(fromNumber);
            if (!appts.isEmpty()) {
                AppointmentService.AppointmentSummary a = appts.get(0);
                return Optional.of("Yes, you're all set. Your appointment with " + a.doctorName + " on " + a.slotDate + " at " + a.startTime + " is confirmed. Anything else I can help with?");
            }
        }

        if (state != null && state.pendingConfirmBook && !state.bookingLocked && !state.bookingCompleted) {
            if (hasCancelOrAbortInSameTurn(normalized)) {
                clearPending(callSid);
                return Optional.of(phrases.abortBooking());
            }
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

        if (state != null && state.pendingConfirmCancel && yesNoClassifier.isAffirmative(userText)) {
            return confirmCancel(callSid, fromNumber, state.cancelPatientName);
        }

        if (state != null && state.pendingConfirmReschedule && yesNoClassifier.isAffirmative(userText)) {
            return confirmReschedule(callSid, fromNumber, state);
        }

        if (state != null && (state.pendingConfirmBook || state.pendingConfirmCancel || state.pendingConfirmReschedule)
                && yesNoClassifier.isNegative(userText)) {
            state.pendingConfirmBook = false;
            state.pendingConfirmCancel = false;
            state.pendingConfirmReschedule = false;
            state.pendingConfirmAbort = true;
            state.currentState = ConversationState.CONFIRM_ABORT;
            return Optional.of(phrases.noChangesAbortChoice());
        }

        if ((state == null || !state.hasAnyPending()) && (normalized.equals("no") || normalized.equals("nope"))) {
            if (conversationSummary != null && !conversationSummary.isEmpty()) {
                String lastMsg = conversationSummary.get(conversationSummary.size() - 1);
                String lastLower = lastMsg.toLowerCase();
                if (lastLower.contains("assistant:") && (lastLower.contains("anything else") || lastLower.contains("help you with"))) {
                    return Optional.of(phrases.goodbye());
                }
            }
        }

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

        ExtractedIntent extracted = extractIntent(userText, conversationSummary, openAiKey, openAiModel);

        if (StringUtils.isNotBlank(extracted.time) && isLikelyPhoneNumber(extracted.time)) {
            extracted.time = null;
            if (StringUtils.isBlank(extracted.patientPhone)) extracted.patientPhone = userText.replaceAll("\\D", "");
        }

        if (extracted.intent.equals("list_doctors") || isListDoctorsRequest(normalized)) {
            return buildListDoctorsResponse();
        }

        if (isClarifyWhatHappenedRequest(normalized) && (state == null || !state.pendingChooseCancelAppointment)) {
            return Optional.of(phrases.clarifyWhatHappened());
        }

        if (extracted.intent.equals("check_appointments")) {
            List<AppointmentService.AppointmentSummary> list = appointmentService.getActiveAppointmentSummaries(fromNumber);
            if (list.isEmpty()) return Optional.of("You don't have any appointments right now.");
            List<AppointmentService.AppointmentSummary> sorted = sortAppointmentsByNearest(list);
            if (list.size() == 1) {
                AppointmentService.AppointmentSummary a = sorted.get(0);
                return Optional.of("Yeah, you've got one — " + a.patientName + " with " + a.doctorName + " on " + a.slotDate + " at " + a.startTime + ".");
            }
            AppointmentService.AppointmentSummary nearest = sorted.get(0);
            return Optional.of("You have " + list.size() + " appointments. Your nearest is " + nearest.patientName + " with " + nearest.doctorName + " on " + nearest.slotDate + " at " + nearest.startTime + ". Want me to list all or help with something specific?");
        }

        if (extracted.intent.equals("ask_availability") && intentClassifier.classify(userText, false) != ConversationIntent.ASK_DOCTOR_INFO) {
            Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
            if (slots.isEmpty()) return Optional.of("No slots at the moment.");
            PendingStateDto s = getPending(callSid);
            String doctorKey = null;
            if (s != null && s.pendingRescheduleDetails && StringUtils.isNotBlank(s.reschedulePatientName)) {
                doctorKey = appointmentService.getActiveAppointmentSummary(fromNumber, s.reschedulePatientName)
                        .flatMap(a -> appointmentService.getAllDoctors().stream()
                                .filter(d -> d.getName().equals(a.doctorName))
                                .findFirst()
                                .map(Doctor::getKey))
                        .orElse(null);
            }
            if (doctorKey == null && StringUtils.isNotBlank(extracted.doctorKey)) {
                String nk = normalizeDoctorKey(extracted.doctorKey);
                if (nk == null) {
                    return Optional.of(buildUnknownDoctorResponse(extracted.doctorKey));
                }
                doctorKey = nk;
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
            PendingStateDto stateForBook = getOrCreate(callSid);
            stateForBook.doctorKey = resolvedDoctorKey;
            List<String> requestedDates = getRequestedDatesFromUserMessage(normalized);
            List<String> samples = buildSlotSamplesForDoctor(slots.get(resolvedDoctorKey), requestedDates);
            if (samples.isEmpty()) {
                return Optional.of("No slots for that doctor on those dates. Want to try another date or doctor?");
            }
            List<Doctor> doctors = appointmentService.getAllDoctors();
            String docName = doctors.stream().filter(d -> d.getKey().equals(resolvedDoctorKey)).findFirst().map(Doctor::getName).orElse(resolvedDoctorKey);
            return Optional.of("Slots for " + docName + " — " + String.join("; ", samples) + ". Pick a time that works.");
        }

        if (extracted.intent.equals("cancel")) {
            List<AppointmentService.AppointmentSummary> list = appointmentService.getActiveAppointmentSummaries(fromNumber);
            if (list.isEmpty()) {
                return Optional.of(phrases.noAppointmentsToCancel());
            }
            PendingStateDto s = getOrCreate(callSid);
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
                        PendingStateDto s = getOrCreate(callSid);
                        s.pendingRescheduleDetails = true;
                        s.reschedulePatientName = extracted.patientName;
                        AppointmentService.AppointmentSummary a = match.get();
                        return Optional.of("Your appointment for " + a.patientName + " is with " + a.doctorName + " on " + a.slotDate + " at " + a.startTime + ". Please say the new date and time you want.");
                    }
                }
                PendingStateDto s = getOrCreate(callSid);
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
                PendingStateDto s = getOrCreate(callSid);
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
            PendingStateDto s = getOrCreate(callSid);
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
            	PendingStateDto pending = getOrCreate(callSid);
                pending.doctorKey = doctorKey;
                pending.date = date;
                return Optional.of(phrases.slotUnavailable());
            }

            PendingStateDto s = getOrCreate(callSid);
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
                if (intentClassifier.classifyMulti(userText, false).hasIntent(ConversationIntent.ASK_DOCTOR_INFO)) {
                    return buildDoctorInfoThenNeedNamePhone(s, doctorKey);
                }
                return Optional.of(phrases.needNameAndPhone());
            }
        }

        if (extracted.intent.equals("book")) {
            PendingStateDto s = getOrCreate(callSid);
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
                            if (intentClassifier.classifyMulti(userText, false).hasIntent(ConversationIntent.ASK_DOCTOR_INFO)) {
                                return buildDoctorInfoThenNeedNamePhone(s, s.doctorKey);
                            }
                            return Optional.of(phrases.needNameAndPhone());
                        }
                    }
                }
                return Optional.of(phrases.slotUnavailable());
            }
        }

        return Optional.empty();
    }

    private Optional<String> confirmBook(String callSid, String fromNumber, PendingStateDto state) {
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

    private Optional<String> confirmReschedule(String callSid, String fromNumber, PendingStateDto state) {
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

    private Optional<String> buildDoctorInfoThenNeedNamePhone(PendingStateDto state, String doctorKey) {
        if (StringUtils.isBlank(doctorKey)) return Optional.of(phrases.needNameAndPhone());
        List<Doctor> doctors = appointmentService.getAllDoctors();
        Doctor doc = doctors.stream().filter(d -> doctorKey.equals(d.getKey())).findFirst().orElse(null);
        if (doc == null) return Optional.of(phrases.needNameAndPhone());
        String spec = doctorSpecializationPhrase(doc);
        return Optional.of(doc.getName() + " is our " + spec + " Now, " + phrases.needNameAndPhone().toLowerCase());
    }

    private Optional<String> buildDoctorInfoThenConfirmPrompt(PendingStateDto state) {
        if (StringUtils.isBlank(state.doctorKey)) return Optional.empty();
        List<Doctor> doctors = appointmentService.getAllDoctors();
        Doctor doc = doctors.stream().filter(d -> state.doctorKey.equals(d.getKey())).findFirst().orElse(null);
        if (doc == null) return Optional.empty();
        String spec = doctorSpecializationPhrase(doc);
        return Optional.of(doc.getName() + " is our " + spec + " " + phrases.confirmAfterDoctorInfo());
    }

    private String doctorSpecializationPhrase(Doctor doc) {
        if (doc.getSpecialization() != null && !doc.getSpecialization().isBlank()) {
            return doc.getSpecialization().trim().toLowerCase() + ". ";
        }
        return "general physician. ";
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

    private PendingStateDto getOrCreate(String callSid) {
        return pendingByCall.computeIfAbsent(callSid, k -> new PendingStateDto());
    }

    public void clearPending(String callSid) {
        pendingByCall.remove(callSid);
    }

    public PendingStateDto getPending(String callSid) {
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

    private String normalizeDoctorKey(String userInput) {
        if (userInput == null || userInput.isBlank()) return null;
        String k = userInput.trim().toLowerCase().replace(".", "").replaceAll("\\s+", "");
        List<Doctor> doctors = appointmentService.getAllDoctors();
        for (Doctor d : doctors) {
            if (d.getKey() != null && d.getKey().toLowerCase().replace("-", "").equals(k)) return d.getKey();
            String nameNorm = d.getName() != null ? d.getName().toLowerCase().replace(".", "").replaceAll("\\s+", "") : "";
            if (!nameNorm.isEmpty() && (nameNorm.equals(k) || nameNorm.contains(k) || k.contains(nameNorm.replace("dr", "")))) return d.getKey();
            if (d.getSpecialization() != null && !d.getSpecialization().isBlank()
                    && d.getSpecialization().toLowerCase().contains(k)) return d.getKey();
        }
        if (k.contains("allen") || k.equals("alan")) return doctors.stream().filter(d -> "dr-alan".equals(d.getKey())).findFirst().map(Doctor::getKey).orElse(null);
        if (k.contains("ahmad") || k.contains("ahamed") || k.contains("ahamad")) return doctors.stream().filter(d -> "dr-ahmed".equals(d.getKey())).findFirst().map(Doctor::getKey).orElse(null);
        return null;
    }

    private boolean isListDoctorsRequest(String normalized) {
        return normalized.contains("list") && (normalized.contains("doctor") || normalized.contains("doc"))
                || normalized.contains("what doctors") || normalized.contains("doctor names")
                || normalized.contains("restore") && normalized.contains("doctor")
                || normalized.contains("available doctors");
    }

    private Optional<String> buildListDoctorsResponse() {
        List<Doctor> doctors = appointmentService.getAllDoctors();
        if (doctors.isEmpty()) return Optional.of("We don't have any doctors available at the moment.");
        StringBuilder sb = new StringBuilder("We've got ");
        for (int i = 0; i < doctors.size(); i++) {
            Doctor d = doctors.get(i);
            if (i > 0) sb.append(i == doctors.size() - 1 ? " and " : ", ");
            sb.append(d.getName());
            if (d.getSpecialization() != null && !d.getSpecialization().isBlank()) {
                sb.append(" (").append(d.getSpecialization()).append(")");
            }
        }
        sb.append(". Which one would you like to book with?");
        return Optional.of(sb.toString());
    }

    private boolean isAbortBookingRequest(String normalized) {
        String n = normalized.replace("'", "");  // handle "dont" from speech transcription
        return (n.contains("dont need") || n.contains("don't need")) && (n.contains("appointment") || n.contains("booking"))
                || normalized.contains("cut the appointment") || normalized.contains("cut appointment")
                || normalized.contains("cancel the appointment") || (normalized.contains("cancel") && normalized.contains("appointment"))
                || normalized.contains("no appointment") || normalized.contains("never mind")
                || (normalized.contains("stop") && (normalized.contains("booking") || normalized.contains("appointment")));
    }

    private boolean hasCancelOrAbortInSameTurn(String normalized) {
        return normalized.contains("cancel that") || normalized.contains("no, no. cancel") || normalized.contains("no no cancel")
                || (normalized.contains("cancel") && normalized.contains("no"));
    }

    private boolean isClarifyWhatHappenedRequest(String normalized) {
        return normalized.contains("what you've done") || normalized.contains("what you did") || normalized.contains("clear what")
                || normalized.contains("did you cancel") || normalized.contains("did you book") || normalized.contains("cancelled or booked");
    }

    private static final int MAX_SLOT_SAMPLES = 5;

    private List<String> getRequestedDatesFromUserMessage(String normalized) {
        List<String> out = new ArrayList<>();
        String tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dayAfter = LocalDate.now().plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE);
        boolean wantOtherDates = normalized.contains("other dates") || normalized.contains("any other dates") || normalized.contains("other date");
        if (normalized.contains("tomorrow") || wantOtherDates) out.add(tomorrow);
        if (normalized.contains("day after") || normalized.contains("day after tomorrow") || wantOtherDates) out.add(dayAfter);
        return out;
    }

    private List<String> buildSlotSamplesForDoctor(Map<String, List<String>> byDate, List<String> requestedDates) {
        if (byDate == null || byDate.isEmpty()) return Collections.emptyList();
        List<String> samples = new ArrayList<>();
        List<String> dateOrder = new ArrayList<>(byDate.keySet());
        Collections.sort(dateOrder);
        if (!requestedDates.isEmpty()) {
            for (String d : requestedDates) {
                List<String> times = byDate.get(d);
                if (times != null && !times.isEmpty())
                    samples.add(d + ": " + LlmService.formatSlotsAsRanges(times));
            }
        }
        for (String date : dateOrder) {
            if (samples.size() >= MAX_SLOT_SAMPLES) break;
            if (requestedDates.contains(date)) continue;
            List<String> times = byDate.get(date);
            if (times != null && !times.isEmpty())
                samples.add(date + ": " + LlmService.formatSlotsAsRanges(times));
        }
        return samples;
    }

    private List<AppointmentService.AppointmentSummary> sortAppointmentsByNearest(List<AppointmentService.AppointmentSummary> list) {
        List<AppointmentService.AppointmentSummary> copy = new ArrayList<>(list);
        copy.sort(Comparator
                .comparing((AppointmentService.AppointmentSummary a) -> a.slotDate)
                .thenComparing(a -> a.startTime != null ? a.startTime : ""));
        return copy;
    }

    private boolean isAlreadyBookedAcknowledgment(String normalized) {
        return normalized.contains("already booked") || normalized.contains("have already booked")
                || normalized.contains("we booked") || normalized.contains("appointment confirmed")
                || normalized.contains("appointment is confirmed") || normalized.contains("we have booked");
    }

    private String buildUnknownDoctorResponse(String rawDoctorName) {
        List<Doctor> doctors = appointmentService.getAllDoctors();
        String names = doctors.stream().map(Doctor::getName).filter(Objects::nonNull).reduce((a, b) -> a + ", " + b).orElse("");
        return "We don't have " + (rawDoctorName != null ? rawDoctorName.trim() : "that doctor") + ". We have " + names + ". Which would you like?";
    }

    private String buildDoctorNotFoundMessage() {
        List<Doctor> doctors = appointmentService.getAllDoctors();
        if (doctors.isEmpty()) return "We don't have any doctors available at the moment.";
        String names = doctors.stream().map(Doctor::getName).filter(n -> n != null && !n.isBlank()).reduce((a, b) -> a + ", " + b).orElse("");
        return "Doctor not found. We have " + names + ".";
    }

    private String normalizeTimeForSlot(String t) {
        if (t == null || t.isBlank()) return null;
        t = t.trim();
        if (t.contains(" to ")) t = t.substring(0, t.indexOf(" to ")).trim();

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

    private String matchSlotTime(List<String> available, String userTime) {
        if (available == null) return null;
        String norm = normalizeTimeForSlot(userTime);
        if (norm == null) return null;
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
                "intent: book|cancel|reschedule|check_appointments|ask_availability|list_doctors|none\n" +
                "check_appointments: 'do I have an appointment', 'appointment confirmed', 'is appointment confirmed', 'what are my appointments'.\n" +
                "ask_availability: 'what dates/times available', 'available slots' — NOT when user asks to list doctors.\n" +
                "list_doctors: 'list doctor names', 'what doctors are available', 'doctor names', 'list doctors', 'what doctors do you have'.\n" +
                "doctorKey: Map user's doctor name to one of these keys from DB: [" + doctorList + "]. Use context to resolve variants (e.g. Allen/Alan, John/Jon). If user names a doctor NOT in our list (e.g. Dr Charles), output that name as doctorKey so we can inform them. For ask_availability, if AI just recommended a doctor and user says 'yes/sure/check slots', extract that doctor's key from the AI message.\n" +
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

    public Optional<String> trySetPendingBook(String callSid, String fromNumber, String doctorKey, String date, String time, String name, String phone) {
        String d = normalizeDate(date);
        if (d == null) return Optional.empty();

        Map<String, Map<String, List<String>>> available = appointmentService.getAvailableSlotsForNextWeek();
        if (!available.containsKey(doctorKey)) return Optional.empty();
        List<String> byDate = available.get(doctorKey).get(d);
        if (byDate == null || !byDate.contains(time)) return Optional.empty();

        PendingStateDto s = getOrCreate(callSid);
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
