package com.ai.receptionist.service;

import com.ai.receptionist.component.ResponsePhrases;
import com.ai.receptionist.conversation.IntentResult;
import com.ai.receptionist.dto.PendingStateDto;
import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.utils.ConversationIntent;
import com.ai.receptionist.utils.ConversationState;
import com.ai.receptionist.utils.YesNoResult;
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

        userText = normalizeAppointmentTypo(userText);
        String normalized = userText.toLowerCase().trim();
        PendingStateDto state = pendingByCall.get(callSid);

        // If user spoke in a non-English language or the text is mostly non-ASCII,
        // do NOT try to interpret slots/doctor intents. Ask them to repeat in English instead.
        if (isLikelyForeignOrUnclearText(userText)) {
            return Optional.of(phrases.unclearAskAgain());
        }

        if (wantsToEndCall(normalized)) {
            return Optional.of(phrases.goodbye());
        }

        // Off-topic / general-knowledge questions: let LLM answer and resume flow
        if (isGeneralKnowledgeQuestion(normalized)) {
            return Optional.empty();
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
            List<AppointmentService.AppointmentSummary> appts = appointmentService.getUpcomingAppointmentSummaries(fromNumber);
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
                Optional<AppointmentService.AppointmentSummary> match = appointmentService.getUpcomingAppointmentSummary(fromNumber, name);
                if (match.isPresent()) {
                    state.pendingChooseCancelAppointment = false;
                    state.pendingConfirmCancel = true;
                    state.cancelPatientName = match.get().patientName;
                    AppointmentService.AppointmentSummary a = match.get();
                    return Optional.of(phrases.confirmCancelPrompt(a.patientName, a.doctorName, a.slotDate, a.startTime));
                }
            }
        }

        if (state != null && state.pendingChooseRescheduleAppointment) {
            String name = extractIntent(userText, conversationSummary, openAiKey, openAiModel).patientName;
            if (StringUtils.isNotBlank(name)) {
                Optional<AppointmentService.AppointmentSummary> match = appointmentService.getUpcomingAppointmentSummary(fromNumber, name);
                if (match.isPresent()) {
                    state.pendingChooseRescheduleAppointment = false;
                    state.pendingRescheduleDetails = true;
                    state.reschedulePatientName = match.get().patientName;
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
            // If the user says NO to a cancel confirmation, exit the cancel flow completely
            // and clear any pending cancel state so we don't re-enter a cancel loop.
            if (state.pendingConfirmCancel && state.currentState == ConversationState.CANCEL_APPOINTMENT) {
                clearPending(callSid);
                return Optional.of("No problem, I won't cancel it. Do you want to try something else, or should I end the call?");
            }

            // For booking / reschedule confirmations, fall back to the generic "no changes" flow.
            state.pendingConfirmBook = false;
            state.pendingConfirmCancel = false;
            state.pendingConfirmReschedule = false;
            state.pendingConfirmAbort = true;
            state.currentState = ConversationState.CONFIRM_ABORT;
            return Optional.of(phrases.noChangesAbortChoice());
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

        // Handle short YES/NO and pure "thanks" responses contextually
        // *before* heavy intent extraction, to avoid accidental cancel/reschedule triggers.
        YesNoResult yesNo = yesNoClassifier.classify(userText);
        boolean isShortYesNo = userText.trim().length() <= 15 && yesNo != YesNoResult.UNKNOWN;

        // Pure "thanks"/"thank you" handling – often comes after "anything else?" or "end the call?"
        if (isPureThanks(normalized)) {
            if (conversationSummary != null && !conversationSummary.isEmpty()) {
                String lastMsg = conversationSummary.get(conversationSummary.size() - 1).toLowerCase();
                if (lastMsg.contains("assistant:")
                        && (lastMsg.contains("anything else") || lastMsg.contains("help you with") || lastMsg.contains("end the call"))) {
                    clearPending(callSid);
                    return Optional.of(phrases.goodbye());
                }
            }
            return Optional.of("You're welcome. Is there anything else I can help you with?");
        }

        if ((state == null || !state.hasAnyPending()) && isShortYesNo) {
            Optional<String> handledYesNo = handleShortYesNoWithoutPending(callSid, yesNo, conversationSummary);
            if (handledYesNo.isPresent()) {
                return handledYesNo;
            }
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
            List<AppointmentService.AppointmentSummary> list = appointmentService.getUpcomingAppointmentSummaries(fromNumber);
            if (list.isEmpty()) return Optional.of("You don't have any upcoming appointments.");
            List<AppointmentService.AppointmentSummary> sorted = sortAppointmentsByNearest(list);
            if (list.size() == 1) {
                AppointmentService.AppointmentSummary a = sorted.get(0);
                return Optional.of("Yeah, you've got one — " + a.patientName + " with " + a.doctorName + " on " + a.slotDate + " at " + a.startTime + ".");
            }
            AppointmentService.AppointmentSummary nearest = sorted.get(0);
            return Optional.of("You have " + list.size() + " upcoming appointments. Your nearest is " + nearest.patientName + " with " + nearest.doctorName + " on " + nearest.slotDate + " at " + nearest.startTime + ". Want me to list all or help with something specific?");
        }

        if (extracted.intent.equals("ask_availability") && intentClassifier.classify(userText, false) != ConversationIntent.ASK_DOCTOR_INFO) {
            Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
            if (slots.isEmpty()) return Optional.of("No slots at the moment.");
            PendingStateDto s = getPending(callSid);
            String doctorKey = null;
            if (s != null && s.pendingRescheduleDetails && StringUtils.isNotBlank(s.reschedulePatientName)) {
                doctorKey = appointmentService.getUpcomingAppointmentSummary(fromNumber, s.reschedulePatientName)
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
            // If user didn't clearly mention a doctor but we already have a doctor in state,
            // we can reuse it — unless this turn sounds like a fresh symptom-based request
            // (e.g. "nearest throat doctor"), in which case we should NOT force the old doctor.
            if (doctorKey == null && s != null && StringUtils.isNotBlank(s.doctorKey)
                    && !isSymptomBasedDoctorRequest(normalized)) {
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
            List<Doctor> doctors = appointmentService.getAllDoctors();
            String docName = doctors.stream().filter(d -> d.getKey().equals(resolvedDoctorKey)).findFirst().map(Doctor::getName).orElse(resolvedDoctorKey);
            String nearest = buildNearestSlotSentence(docName, slots.get(resolvedDoctorKey), requestedDates);
            if (nearest == null) {
                return Optional.of("No slots for that doctor on those dates. Want to try another date or doctor?");
            }
            return Optional.of(nearest);
        }

        if (extracted.intent.equals("cancel")) {
            // Extra safety: only treat this as an explicit cancel request when the user
            // actually uses a cancel-like word. This prevents LLM extraction from
            // misclassifying generic "no"/"nope"/"thank you" messages as cancel intents.
            if (!containsExplicitCancelWord(normalized)) {
                return Optional.empty();
            }

            List<AppointmentService.AppointmentSummary> list = appointmentService.getUpcomingAppointmentSummaries(fromNumber);
            if (list.isEmpty()) {
                return Optional.of(phrases.noAppointmentsToCancel());
            }
            PendingStateDto s = getOrCreate(callSid);
            s.pendingConfirmBook = false;
            s.pendingConfirmReschedule = false;
            List<AppointmentService.AppointmentSummary> sorted = sortAppointmentsByNearest(list);
            if (list.size() == 1) {
                s.pendingConfirmCancel = true;
                s.cancelPatientName = sorted.get(0).patientName;
                s.currentState = ConversationState.CANCEL_APPOINTMENT;
                AppointmentService.AppointmentSummary a = sorted.get(0);
                return Optional.of(phrases.confirmCancelPrompt(a.patientName, a.doctorName, a.slotDate, a.startTime));
            }
            if (StringUtils.isNotBlank(extracted.patientName)) {
                Optional<AppointmentService.AppointmentSummary> match = appointmentService.getUpcomingAppointmentSummary(fromNumber, extracted.patientName);
                if (match.isPresent()) {
                    s.pendingConfirmCancel = true;
                    s.cancelPatientName = match.get().patientName;
                    AppointmentService.AppointmentSummary a = match.get();
                    return Optional.of(phrases.confirmCancelPrompt(a.patientName, a.doctorName, a.slotDate, a.startTime));
                }
            }
            s.pendingChooseCancelAppointment = true;
            s.pendingConfirmCancel = false;
            return Optional.of("You have " + list.size() + " upcoming appointments. Please say the patient name for the one you want to cancel, for example 'cancel John Cena'.");
        }

        if (state != null && state.pendingRescheduleDetails) {
            Optional<AppointmentService.AppointmentSummary> existingSummary = appointmentService.getUpcomingAppointmentSummary(
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
            List<AppointmentService.AppointmentSummary> list = appointmentService.getUpcomingAppointmentSummaries(fromNumber);
            if (list.isEmpty()) {
                return Optional.of(phrases.noAppointmentsToReschedule());
            }
            List<AppointmentService.AppointmentSummary> sorted = sortAppointmentsByNearest(list);
            if (isNearestAppointmentRequest(normalized)) {
                AppointmentService.AppointmentSummary nearest = sorted.get(0);
                PendingStateDto s = getOrCreate(callSid);
                s.pendingRescheduleDetails = true;
                s.pendingChooseRescheduleAppointment = false;
                s.reschedulePatientName = nearest.patientName;
                return Optional.of("Your nearest appointment is " + nearest.patientName + " with " + nearest.doctorName + " on " + nearest.slotDate + " at " + nearest.startTime + ". Please say the new date and time you want.");
            }
            if (list.size() > 1) {
                if (StringUtils.isNotBlank(extracted.patientName)) {
                    Optional<AppointmentService.AppointmentSummary> match = appointmentService.getUpcomingAppointmentSummary(fromNumber, extracted.patientName);
                    if (match.isPresent()) {
                        PendingStateDto s = getOrCreate(callSid);
                        s.pendingRescheduleDetails = true;
                        s.reschedulePatientName = match.get().patientName;
                        AppointmentService.AppointmentSummary a = match.get();
                        return Optional.of("Your appointment for " + a.patientName + " is with " + a.doctorName + " on " + a.slotDate + " at " + a.startTime + ". Please say the new date and time you want.");
                    }
                }
                AppointmentService.AppointmentSummary nearest = sorted.get(0);
                PendingStateDto s = getOrCreate(callSid);
                s.pendingChooseRescheduleAppointment = true;
                s.pendingRescheduleDetails = false;
                return Optional.of("You have " + list.size() + " upcoming appointments. Your nearest is " + nearest.patientName + " on " + nearest.slotDate + " at " + nearest.startTime + ". Say that name to reschedule it, or say another patient name.");
            }
            Optional<AppointmentService.AppointmentSummary> existingSummary = Optional.of(sorted.get(0));
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
            AppointmentService.AppointmentSummary a = existingSummary.get();
            s.reschedulePatientName = a.patientName;
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
        return normalized.contains("end call") || normalized.contains("hang up") || normalized.contains("hangup")
                || normalized.contains("goodbye") || normalized.contains("that's all") || normalized.contains("nothing else")
                || (normalized.contains("thank you") && normalized.contains("bye"))
                || (normalized.contains("bye") && normalized.length() <= 15);
    }

    /**
     * Detect general-knowledge or off-topic questions that should be answered by the LLM
     * (with full conversation context) and then resume appointment flow. Prevents booking
     * logic from misclassifying e.g. "today's weather" as slot intent and returning wrong replies.
     */
    private boolean isGeneralKnowledgeQuestion(String normalized) {
        if (normalized == null || normalized.length() < 4) return false;
        String n = normalized.toLowerCase();
        // Weather
        if (n.contains("weather") || n.contains("temperature") || n.contains("rain") || n.contains("forecast")) return true;
        // Time / date as general question (not slot choice)
        if ((n.contains("what time") || n.contains("what is the time") || n.contains("current time")) && !n.contains("slot") && !n.contains("appointment")) return true;
        // General knowledge / news
        if (n.contains("prime minister") || n.contains("chief minister") || n.contains("president of") || n.contains("who is the")) return true;
        if (n.contains("capital of") || n.contains("news") || (n.contains("today's date") && !n.contains("appointment"))) return true;
        // Greeting / small talk
        if (n.matches("^(how are you|how do you do|what('s| is) up)\\s*[?.!]*$") || n.equals("how are you")) return true;
        // Clinic info that is not slot/booking
        if ((n.contains("what time") && n.contains("close")) || (n.contains("opening hours") && !n.contains("slot"))) return true;
        return false;
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
        String lower = userInput.trim().toLowerCase();
        String k = lower.replace(".", "").replaceAll("\\s+", "");
        List<Doctor> doctors = appointmentService.getAllDoctors();

        // Symptom / lay-term mapping to specializations, driven by DB values.
        // This keeps behaviour data-driven: we only map to a doctor if a matching
        // specialization actually exists in the database.
        String targetSpecialization = null;
        if (lower.contains("tooth") || lower.contains("teeth") || lower.contains("dental")) {
            targetSpecialization = "dentist";
        } else if (lower.contains("heart") || lower.contains("cardio") || lower.contains("chest pain")) {
            targetSpecialization = "cardiologist";
        } else if (lower.contains("throat") || lower.contains("ent") || lower.contains("ear") || lower.contains("nose")) {
            targetSpecialization = "ent";
        }
        if (targetSpecialization != null) {
            for (Doctor d : doctors) {
                String spec = d.getSpecialization() != null ? d.getSpecialization().toLowerCase() : "";
                if (spec.contains(targetSpecialization)) {
                    return d.getKey();
                }
            }
        }

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

    private boolean isNearestAppointmentRequest(String normalized) {
        return normalized.contains("nearest appointment") || normalized.contains("next appointment")
                || normalized.contains("upcoming appointment") || normalized.contains("my nearest")
                || normalized.contains("reschedule my nearest") || normalized.contains("reschedule nearest");
    }

    /** Normalize common STT/speech typos so intent is correct (e.g. "apartment" -> "appointment"). */
    private String normalizeAppointmentTypo(String userText) {
        if (userText == null || userText.isEmpty()) return userText;
        return userText.replaceAll("(?i)\\bapartment\\b", "appointment");
    }

    private List<String> getRequestedDatesFromUserMessage(String normalized) {
        List<String> out = new ArrayList<>();
        String tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dayAfter = LocalDate.now().plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE);
        boolean wantOtherDates = normalized.contains("other dates") || normalized.contains("any other dates") || normalized.contains("other date");
        if (normalized.contains("tomorrow") || wantOtherDates) out.add(tomorrow);
        if (normalized.contains("day after") || normalized.contains("day after tomorrow") || wantOtherDates) out.add(dayAfter);
        return out;
    }

    /**
     * Build a single, user-friendly sentence describing the doctor's nearest available slot.
     * If requestedDates is empty, we look across all future dates; otherwise we try those dates first.
     */
    private String buildNearestSlotSentence(String doctorName, Map<String, List<String>> byDate, List<String> requestedDates) {
        if (byDate == null || byDate.isEmpty()) return null;

        List<String> dateOrder = new ArrayList<>(byDate.keySet());
        Collections.sort(dateOrder);

        String chosenDate = null;
        if (requestedDates != null && !requestedDates.isEmpty()) {
            for (String d : dateOrder) {
                if (requestedDates.contains(d)) {
                    List<String> times = byDate.get(d);
                    if (times != null && !times.isEmpty()) {
                        chosenDate = d;
                        break;
                    }
                }
            }
        }
        if (chosenDate == null) {
            for (String d : dateOrder) {
                List<String> times = byDate.get(d);
                if (times != null && !times.isEmpty()) {
                    chosenDate = d;
                    break;
                }
            }
        }
        if (chosenDate == null) return null;

        List<String> times = new ArrayList<>(byDate.get(chosenDate));
        if (times.isEmpty()) return null;
        Collections.sort(times);
        String time = times.get(0);

        LocalDate today = LocalDate.now();
        String dateText;
        try {
            LocalDate d = LocalDate.parse(chosenDate);
            if (d.equals(today)) {
                dateText = "today";
            } else if (d.equals(today.plusDays(1))) {
                dateText = "tomorrow";
            } else {
                dateText = chosenDate;
            }
        } catch (Exception e) {
            dateText = chosenDate;
        }

        return doctorName + "'s nearest availability is " + dateText + " at " + time + ". Would that work?";
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

    /** Detect symptom-style phrases that imply a specialty (throat, tooth, heart, etc.) rather than a named doctor. */
    private boolean isSymptomBasedDoctorRequest(String normalized) {
        if (normalized == null || normalized.isBlank()) return false;
        String n = normalized.toLowerCase();
        return n.contains("throat")
                || n.contains("ear pain")
                || n.contains("earache")
                || n.contains("ear problem")
                || n.contains("tooth")
                || n.contains("teeth")
                || n.contains("dental")
                || n.contains("heart")
                || n.contains("chest pain");
    }

    /**
     * Detect whether the user explicitly expressed a desire to cancel, using
     * plain-text keywords, independent of LLM extraction. This keeps cancel
     * behaviour grounded in what the caller actually said.
     */
    private boolean containsExplicitCancelWord(String normalized) {
        if (normalized == null || normalized.isBlank()) return false;
        String n = normalized.replace("'", ""); // handle "don't" / "dont"
        return n.contains("cancel")
                || n.contains("cut appointment")
                || n.contains("cut the appointment")
                || n.contains("remove appointment")
                || n.contains("delete appointment")
                || n.contains("dont need appointment")
                || n.contains("don't need appointment");
    }

    /** Heuristic: input is mostly non-ASCII characters or has no Latin letters -> likely foreign/unclear speech. */
    private boolean isLikelyForeignOrUnclearText(String text) {
        if (text == null || text.isBlank()) return false;
        int nonAscii = 0;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) > 127) nonAscii++;
        }
        boolean noLatinLetters = !text.matches(".*[A-Za-z].*");
        return (nonAscii > 0 && nonAscii * 2 >= len) || noLatinLetters;
    }

    private boolean isPureThanks(String normalized) {
        String n = normalized.replaceAll("[!.]", "").trim();
        return n.equals("thank you") || n.equals("thanks") || n.equals("thank you so much") || n.equals("thanks a lot");
    }

    /**
     * Map short YES/NO answers to the last assistant question, when no booking/cancel state is pending.
     * Prevents "No"/"Nope" from accidentally being treated as a CANCEL intent on an existing appointment.
     */
    private Optional<String> handleShortYesNoWithoutPending(String callSid, YesNoResult yesNo, List<String> conversationSummary) {
        if (conversationSummary == null || conversationSummary.isEmpty()) {
            return Optional.empty();
        }
        String lastMsg = conversationSummary.get(conversationSummary.size() - 1).toLowerCase();
        boolean fromAssistant = lastMsg.contains("assistant:");

        if (!fromAssistant) {
            return Optional.empty();
        }

        if (yesNo == YesNoResult.NO) {
            if (lastMsg.contains("anything else") || lastMsg.contains("help you with")) {
                clearPending(callSid);
                return Optional.of(phrases.goodbye());
            }
            if (lastMsg.contains("end the call")) {
                clearPending(callSid);
                return Optional.of(phrases.goodbye());
            }
            // Generic "no" that isn't about ending the call – acknowledge but keep the call alive.
            return Optional.of("No problem. How else can I help you?");
        }

        if (yesNo == YesNoResult.YES) {
            if (lastMsg.contains("end the call")) {
                clearPending(callSid);
                return Optional.of(phrases.goodbye());
            }
            // Generic "yes" – keep conversation open and nudge user.
            return Optional.of("Sure. What would you like to do?");
        }

        return Optional.empty();
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
