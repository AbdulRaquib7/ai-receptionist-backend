package com.ai.receptionist.service;

import com.ai.receptionist.component.ResponsePhrases;
import com.ai.receptionist.conversation.IntentResult;
import com.ai.receptionist.dto.FlowResponse;
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
			YesNoClassifierService yesNoClassifier, ResponsePhrases phrases, IntentClassifier intentClassifier,
			IntentPriorityResolverService intentPriorityResolver) {
		this.appointmentService = appointmentService;
		this.restTemplate = builder.build();
		this.yesNoClassifier = yesNoClassifier;
		this.phrases = phrases;
		this.intentClassifier = intentClassifier;
		this.intentPriorityResolver = intentPriorityResolver;
	}

public Optional<FlowResponse> processUserMessage(
        String callSid,
        String fromNumber,
        String userText,
        List<String> conversationSummary,
        String openAiKey,
        String openAiModel) {

    if (StringUtils.isBlank(userText))
        return Optional.empty();

    userText = normalizeAppointmentTypo(userText);
    String normalized = userText.toLowerCase().trim();

    PendingStateDto state = pendingByCall.get(callSid);

    if (state != null && isAbortBookingRequest(normalized)) {
        clearPending(callSid);
        log.debug("[{}] flow: ABORT_BOOKING", callSid);
        return Optional.of(FlowResponse.of(FlowResponse.Type.ABORT_BOOKING));
    }

    if (wantsToEndCall(normalized)) {
        clearPending(callSid);
        log.debug("[{}] flow: GOODBYE", callSid);
        return Optional.of(FlowResponse.of(FlowResponse.Type.GOODBYE));
    }

    if (isFarewellOrDeclineMoreHelp(normalized, conversationSummary)) {
        clearPending(callSid);
        log.debug("[{}] flow: GOODBYE (farewell after anything else)", callSid);
        return Optional.of(FlowResponse.of(FlowResponse.Type.GOODBYE));
    }

    YesNoResult yesNo = yesNoClassifier.classify(userText);

    if (state != null && state.pendingConfirmBook) {

        if (yesNo == YesNoResult.YES) {
            return confirmBook(callSid, fromNumber, state);
        }

        if (yesNo == YesNoResult.NO) {
            state.pendingConfirmBook = false;
            log.debug("[{}] flow: CONFIRM_BOOKING declined", callSid);
            return Optional.of(FlowResponse.message("differentTime"));
        }
    }

    if (state != null
            && isOtherDatesRequest(normalized)
            && state.lastSuggestedDoctorKey != null) {

        Map<String, Map<String, List<String>>> slots =
                appointmentService.getAvailableSlotsForNextWeek();

        Map<String, List<String>> byDate =
                slots.get(state.lastSuggestedDoctorKey);

        if (byDate == null || byDate.isEmpty())
            return Optional.of(FlowResponse.message("noSlots"));

        List<String> dates = new ArrayList<>(byDate.keySet());
        dates.sort(Comparator.comparing(LocalDate::parse));

        if (state.lastSuggestedDate != null) {
            dates.remove(state.lastSuggestedDate);
        }

        if (dates.isEmpty())
            return Optional.of(FlowResponse.message("noOtherDates"));

        log.debug("[{}] flow: OFFER_OTHER_DATES", callSid);
        return Optional.of(FlowResponse.offerOtherDates(String.join(" and ", dates)));
    }

    if (isLikelyForeignOrUnclearText(userText)) {
        return Optional.of(FlowResponse.of(FlowResponse.Type.REPEAT));
    }

    if (isBookingWithDoctorHint(normalized)) {
        String fastDoctorKey = normalizeDoctorKey(userText);
        if (fastDoctorKey != null) {
            Optional<FlowResponse> fast = suggestNearestSlotForDoctor(callSid, fastDoctorKey);
            if (fast.isPresent()) {
                log.debug("[{}] flow: SUGGEST_SLOT (fast path) doctor={}", callSid, fastDoctorKey);
                return fast;
            }
        }
    }

    ExtractedIntent extracted =
            extractIntent(userText, conversationSummary, openAiKey, openAiModel);

    if (extracted.isGeneralQuestion) {

        if (state != null && state.hasAnyPending()) {
            return Optional.of(FlowResponse.of(FlowResponse.Type.RETURN_TO_FLOW));
        }

        boolean noBookingFields = extracted.doctorKey == null && extracted.date == null
                && extracted.time == null && extracted.patientName == null && extracted.patientPhone == null;
        if ("none".equals(extracted.intent) && noBookingFields) {
            return Optional.empty();
        }
    }

    if ("ask_availability".equals(extracted.intent) || "book".equals(extracted.intent)) {

        String doctorKey = extracted.doctorKey;
        if (doctorKey == null && "book".equals(extracted.intent)) {
            log.debug("[{}] flow: LIST_DOCTORS (book, no doctor)", callSid);
            return buildListDoctorsResponse();
        }
        if (doctorKey == null)
            return Optional.of(FlowResponse.message("whichDoctor"));

        Optional<FlowResponse> slot = suggestNearestSlotForDoctor(callSid, doctorKey);
        if (slot.isPresent()) return slot;
        if (!appointmentService.getAvailableSlotsForNextWeek().containsKey(doctorKey))
            return Optional.of(FlowResponse.message("doctorNoAvailability"));
        return Optional.of(FlowResponse.message("noAvailableSlotsFound"));
    }

    if ("list_doctors".equals(extracted.intent)) {
        log.debug("[{}] flow: LIST_DOCTORS", callSid);
        return buildListDoctorsResponse();
    }

    /* =========================================================
       ✅ CONFIRMATION AFTER DETAILS
       ========================================================= */

    if (state != null
            && state.lastSuggestedDoctorKey != null
            && state.lastSuggestedDate != null
            && state.lastSuggestedTime != null
            && extracted.patientName != null
            && extracted.patientPhone != null) {
        Optional<FlowResponse> tryBook = trySetPendingBook(callSid, fromNumber,
                state.lastSuggestedDoctorKey, state.lastSuggestedDate, state.lastSuggestedTime,
                extracted.patientName, extracted.patientPhone);
        if (tryBook.isPresent()) {
            log.debug("[{}] flow: CONFIRM_BOOKING (from name/phone after slot)", callSid);
            return tryBook;
        }
    }

    if (state != null && state.pendingNeedNamePhone) {

        if (extracted.patientName != null)
            state.patientName = extracted.patientName;

        if (extracted.patientPhone != null)
            state.patientPhone = extracted.patientPhone;

        if (state.patientName != null && state.patientPhone != null) {
            state.pendingNeedNamePhone = false;
            state.pendingConfirmBook = true;

            List<Doctor> doctorsForName = appointmentService.getAllDoctors();
            String docName = doctorsForName.stream().filter(d -> state.doctorKey != null && state.doctorKey.equals(d.getKey())).findFirst().map(Doctor::getName).orElse(state.doctorKey);

            log.debug("[{}] flow: CONFIRM_BOOKING", callSid);
            return Optional.of(FlowResponse.confirmBooking(docName, state.date, state.time));
        }
    }

    /* =========================================================
       ✅ UNCLEAR INPUT DURING FLOW
       ========================================================= */

    if (state != null && state.hasAnyPending()) {
        return Optional.of(FlowResponse.of(FlowResponse.Type.REPEAT));
    }

    return Optional.empty();
}



	private Optional<FlowResponse> confirmBook(String callSid, String fromNumber, PendingStateDto state) {
		if (!state.pendingConfirmBook) {
			throw new IllegalStateException("Booking attempted without confirmation.");
		}
		if (state.bookingLocked) {
			return Optional.of(FlowResponse.of(FlowResponse.Type.CONFIRMED, Map.of("date", state.date, "time", state.time), false));
		}
		if (StringUtils.isBlank(state.doctorKey) || state.date == null || StringUtils.isBlank(state.time)) {
			clearPending(callSid);
			return Optional.of(FlowResponse.message("tryAgainDetails"));
		}
		String twilioPhone = StringUtils.isNotBlank(fromNumber) ? fromNumber : state.patientPhone;
		Optional<Appointment> result = appointmentService.bookAppointment(twilioPhone, state.patientName,
				state.patientPhone, state.doctorKey, state.date, state.time);
		if (result.isPresent()) {
			state.bookingLocked = true;
			state.bookingCompleted = true;
			state.currentState = ConversationState.COMPLETED;
			log.info("Booked appointment for callSid={} doctor={} date={} time={}", callSid, state.doctorKey,
					state.date, state.time);
			Map<String, Object> p = new HashMap<>();
			p.put("date", state.date);
			p.put("time", state.time);
			return Optional.of(FlowResponse.of(FlowResponse.Type.CONFIRMED, p, false));
		}
		return Optional.of(FlowResponse.of(FlowResponse.Type.SLOT_UNAVAILABLE));
	}

	private Optional<FlowResponse> confirmCancel(String callSid, String fromNumber, String patientName) {
		boolean ok = appointmentService.cancelAppointment(fromNumber, patientName);
		clearPending(callSid);
		if (ok) {
			return Optional.of(FlowResponse.of(FlowResponse.Type.CANCELLED));
		}
		return Optional.of(FlowResponse.message("couldntCancel"));
	}

	private Optional<FlowResponse> confirmReschedule(String callSid, String fromNumber, PendingStateDto state) {
		if (StringUtils.isBlank(state.rescheduleDoctorKey) || state.rescheduleDate == null
				|| StringUtils.isBlank(state.rescheduleTime)) {
			clearPending(callSid);
			return Optional.of(FlowResponse.message("tryAgainNewSlot"));
		}
		Optional<Appointment> result = appointmentService.rescheduleAppointment(fromNumber, state.reschedulePatientName,
				state.rescheduleDoctorKey, state.rescheduleDate, state.rescheduleTime);
		clearPending(callSid);
		if (result.isPresent()) {
			return Optional.of(FlowResponse.of(FlowResponse.Type.RESCHEDULED));
		}
		return Optional.of(FlowResponse.of(FlowResponse.Type.SLOT_UNAVAILABLE));
	}

	private Optional<FlowResponse> buildDoctorInfoThenNeedNamePhone(PendingStateDto state, String doctorKey) {
		if (StringUtils.isBlank(doctorKey))
			return Optional.of(FlowResponse.of(FlowResponse.Type.ASK_NAME));
		List<Doctor> doctors = appointmentService.getAllDoctors();
		Doctor doc = doctors.stream().filter(d -> doctorKey.equals(d.getKey())).findFirst().orElse(null);
		if (doc == null)
			return Optional.of(FlowResponse.of(FlowResponse.Type.ASK_NAME));
		String spec = doctorSpecializationPhrase(doc);
		return Optional.of(FlowResponse.suggestDoctor(doctorKey, doc.getName(), spec));
	}

	private Optional<FlowResponse> buildDoctorInfoThenConfirmPrompt(PendingStateDto state) {
		if (StringUtils.isBlank(state.doctorKey))
			return Optional.empty();
		List<Doctor> doctors = appointmentService.getAllDoctors();
		Doctor doc = doctors.stream().filter(d -> state.doctorKey.equals(d.getKey())).findFirst().orElse(null);
		if (doc == null)
			return Optional.empty();
		String spec = doctorSpecializationPhrase(doc);
		return Optional.of(FlowResponse.confirmBooking(doc.getName(), state.date, state.time));
	}

	private String doctorSpecializationPhrase(Doctor doc) {
		if (doc.getSpecialization() != null && !doc.getSpecialization().isBlank()) {
			return doc.getSpecialization().trim().toLowerCase() + ". ";
		}
		return "general physician. ";
	}

	private boolean wantsToEndCall(String normalized) {
	    if (normalized == null) return false;

	    String n = normalized.toLowerCase();

	    return n.contains("end call")
	            || n.contains("hang up")
	            || n.contains("hangup")
	            || n.contains("goodbye")
	            || n.contains("bye")
	            || n.contains("that's all")
	            || n.contains("nothing else")
	            || n.contains("ok bye")
	            || n.contains("thank you bye")
	            || n.equals("bye")
	            || n.equals("goodbye");
	}


	/**
	 * True when the last assistant turn was offering more help ("anything else?",
	 * etc.) and the user is declining or saying goodbye (no, that's fine, peace,
	 * god bless, etc.). Ensures we return goodbye instead of misrouting to "slot's
	 * taken" or other flow.
	 */
	private boolean isFarewellOrDeclineMoreHelp(String normalized, List<String> conversationSummary) {
		if (normalized == null || conversationSummary == null || conversationSummary.isEmpty())
			return false;
		String last = conversationSummary.get(conversationSummary.size() - 1).toLowerCase();
		if (!last.contains("assistant:"))
			return false;
		boolean assistantOfferedMore = last.contains("anything else") || last.contains("help you with")
				|| last.contains("can i help") || last.contains("anything else i can");
		if (!assistantOfferedMore)
			return false;
		String n = normalized.toLowerCase().replaceAll("[.!?]", "").trim();
		if (n.equals("no") || n.equals("nope") || n.equals("nah") || n.equals("no thanks") || n.equals("no thank you"))
			return true;
		if (n.equals("that's fine") || n.equals("thats fine") || n.equals("no that's fine") || n.equals("its fine"))
			return true;
		if (n.contains("peace") || n.contains("god bless") || n.contains("have a good day") || n.contains("you too"))
			return true;
		if (n.contains("nothing else") || n.contains("that's all"))
			return true;
		return false;
	}

	private boolean wantsToStartOver(String normalized) {
		return normalized.contains("something else") || normalized.contains("start over")
				|| normalized.contains("continue") || normalized.contains("try again") || normalized.contains("yes")
				|| normalized.equals("sure");
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

		d = d.toLowerCase()
			 .replaceAll("(yeah|i need|i want|for|on|about)", "")
			 .trim();

		if (d.matches("\\d{4}-\\d{2}-\\d{2}"))
			return d;

		if (d.equals("today"))
			return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

		if (d.equals("tomorrow"))
			return LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

		if (d.contains("day after"))
			return LocalDate.now().plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE);

		return null;
	}

	private String normalizeDoctorKey(String userInput) {
		if (userInput == null || userInput.isBlank())
			return null;
		String lower = userInput.trim().toLowerCase();
		String k = lower.replace(".", "").replaceAll("\\s+", "");
		List<Doctor> doctors = appointmentService.getAllDoctors();

		// Symptom / lay-term mapping to specializations, driven by DB values.
		// This keeps behaviour data-driven: we only map to a doctor if a matching
		// specialization actually exists in the database.
		String targetSpecialization = null;
		if (lower.contains("tooth") || lower.contains("teeth") || lower.contains("dental") || lower.contains("dentist")) {
			targetSpecialization = "dentist";
		} else if (lower.contains("heart") || lower.contains("cardio") || lower.contains("chest pain")) {
			targetSpecialization = "cardiologist";
		} else if (lower.contains("throat") || lower.contains("ent") || lower.contains("ear")
				|| lower.contains("nose")) {
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
			if (d.getKey() != null && d.getKey().toLowerCase().replace("-", "").equals(k))
				return d.getKey();
			String nameNorm = d.getName() != null ? d.getName().toLowerCase().replace(".", "").replaceAll("\\s+", "")
					: "";
			if (!nameNorm.isEmpty()
					&& (nameNorm.equals(k) || nameNorm.contains(k) || k.contains(nameNorm.replace("dr", ""))))
				return d.getKey();
			if (d.getSpecialization() != null && !d.getSpecialization().isBlank()
					&& d.getSpecialization().toLowerCase().contains(k))
				return d.getKey();
		}
		if (k.contains("allen") || k.equals("alan"))
			return doctors.stream().filter(d -> "dr-alan".equals(d.getKey())).findFirst().map(Doctor::getKey)
					.orElse(null);
		if (k.contains("ahmad") || k.contains("ahamed") || k.contains("ahamad"))
			return doctors.stream().filter(d -> "dr-ahmed".equals(d.getKey())).findFirst().map(Doctor::getKey)
					.orElse(null);
		return null;
	}

	private boolean isBookingWithDoctorHint(String normalized) {
		if (normalized == null) return false;
		boolean booking = normalized.contains("book") || normalized.contains("appointment")
				|| normalized.contains("need") || normalized.contains("want") || normalized.contains("get");
		boolean doctorHint = normalized.contains("dentist") || normalized.contains("dental")
				|| normalized.contains("tooth") || normalized.contains("teeth")
				|| normalized.contains("heart") || normalized.contains("cardio")
				|| normalized.contains("ent") || normalized.contains("throat") || normalized.contains("ear") || normalized.contains("nose");
		return booking || doctorHint;
	}

	private Optional<FlowResponse> suggestNearestSlotForDoctor(String callSid, String doctorKey) {
		Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
		if (slots.isEmpty() || !slots.containsKey(doctorKey))
			return Optional.empty();
		Map<String, List<String>> byDate = slots.get(doctorKey);
		List<String> sortedDates = new ArrayList<>(byDate.keySet());
		sortedDates.sort(Comparator.comparing(LocalDate::parse));
		List<Doctor> doctors = appointmentService.getAllDoctors();
		String doctorName = doctors.stream().filter(d -> doctorKey.equals(d.getKey())).findFirst().map(Doctor::getName).orElse(doctorKey);
		for (String d : sortedDates) {
			List<String> times = byDate.get(d);
			if (times != null && !times.isEmpty()) {
				String time = times.get(0);
				PendingStateDto s = getOrCreate(callSid);
				s.lastSuggestedDoctorKey = doctorKey;
				s.lastSuggestedDate = d;
				s.lastSuggestedTime = time;
				return Optional.of(FlowResponse.suggestSlot(doctorKey, doctorName, d, time));
			}
		}
		return Optional.empty();
	}

	private boolean isListDoctorsRequest(String normalized) {
		return normalized.contains("list") && (normalized.contains("doctor") || normalized.contains("doc"))
				|| normalized.contains("what doctors") || normalized.contains("doctor names")
				|| normalized.contains("restore") && normalized.contains("doctor")
				|| normalized.contains("available doctors");
	}

	private Optional<FlowResponse> buildListDoctorsResponse() {
		List<Doctor> doctors = appointmentService.getAllDoctors();
		if (doctors.isEmpty())
			return Optional.of(FlowResponse.message("noDoctorsAvailable"));
		StringBuilder sb = new StringBuilder("We've got ");
		for (int i = 0; i < doctors.size(); i++) {
			Doctor d = doctors.get(i);
			if (i > 0)
				sb.append(i == doctors.size() - 1 ? " and " : ", ");
			sb.append(d.getName());
			if (d.getSpecialization() != null && !d.getSpecialization().isBlank()) {
				sb.append(" (").append(d.getSpecialization()).append(")");
			}
		}
		sb.append(". Which one would you like to book with?");
		return Optional.of(FlowResponse.listDoctors(sb.toString()));
	}

	private boolean isAbortBookingRequest(String text) {
		if (text == null) return false;

		String n = text.toLowerCase().replace("'", "");

		return n.contains("cancel process")
				|| n.contains("cancel booking")
				|| n.contains("cancel this")
				|| n.contains("stop booking")
				|| n.contains("stop this")
				|| n.contains("never mind")
				|| n.contains("forget it")
				|| n.contains("leave it")
				|| n.contains("abort")
				|| n.equals("cancel")
				|| n.equals("stop");
	}


	private boolean hasCancelOrAbortInSameTurn(String normalized) {
		return normalized.contains("cancel that") || normalized.contains("no, no. cancel")
				|| normalized.contains("no no cancel") || (normalized.contains("cancel") && normalized.contains("no"));
	}

	private boolean isClarifyWhatHappenedRequest(String normalized) {
		return normalized.contains("what you've done") || normalized.contains("what you did")
				|| normalized.contains("clear what") || normalized.contains("did you cancel")
				|| normalized.contains("did you book") || normalized.contains("cancelled or booked");
	}

	private boolean isNearestAppointmentRequest(String normalized) {
		return normalized.contains("nearest appointment") || normalized.contains("next appointment")
				|| normalized.contains("upcoming appointment") || normalized.contains("my nearest")
				|| normalized.contains("reschedule my nearest") || normalized.contains("reschedule nearest");
	}

	/**
	 * Normalize common STT/speech typos so intent is correct (e.g. "apartment" ->
	 * "appointment").
	 */
	private String normalizeAppointmentTypo(String userText) {
		if (userText == null || userText.isEmpty())
			return userText;
		return userText.replaceAll("(?i)\\bapartment\\b", "appointment");
	}

	private List<String> getRequestedDatesFromUserMessage(String normalized) {
		List<String> out = new ArrayList<>();
		String tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
		String dayAfter = LocalDate.now().plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE);

		boolean wantOtherDates = normalized.contains("other dates") || normalized.contains("any other dates")
				|| normalized.contains("other date");

		if (normalized.contains("day after tomorrow")) {
			out.add(dayAfter);
			return out;
		}

		if (normalized.contains("tomorrow") || wantOtherDates) {
			out.add(tomorrow);
		}
		if (normalized.contains("day after") || wantOtherDates) {
			out.add(dayAfter);
		}
		return out;
	}

	private String buildNearestSlotSentence(String doctorName, Map<String, List<String>> byDate,
			List<String> requestedDates) {

		if (byDate == null || byDate.isEmpty())
			return null;

		List<String> sortedDates = new ArrayList<>(byDate.keySet());
		Collections.sort(sortedDates);

		if (requestedDates != null && !requestedDates.isEmpty()) {
			for (String d : requestedDates) {
				if (byDate.containsKey(d) && !byDate.get(d).isEmpty()) {
					return doctorName + " is available on " + d + " at " + byDate.get(d).get(0) + ". Would that work?";
				}
			}
			return null;
		}

		for (String d : sortedDates) {
			List<String> times = byDate.get(d);
			if (times != null && !times.isEmpty()) {
				return doctorName + "'s nearest availability is " + d + " at " + times.get(0) + ". Would that work?";
			}
		}

		return null;
	}

	private List<AppointmentService.AppointmentSummary> sortAppointmentsByNearest(
			List<AppointmentService.AppointmentSummary> list) {
		List<AppointmentService.AppointmentSummary> copy = new ArrayList<>(list);
		copy.sort(Comparator.comparing((AppointmentService.AppointmentSummary a) -> a.slotDate)
				.thenComparing(a -> a.startTime != null ? a.startTime : ""));
		return copy;
	}

	private boolean isAlreadyBookedAcknowledgment(String normalized) {
		return normalized.contains("already booked") || normalized.contains("have already booked")
				|| normalized.contains("we booked") || normalized.contains("appointment confirmed")
				|| normalized.contains("appointment is confirmed") || normalized.contains("we have booked");
	}

	private boolean isSymptomBasedDoctorRequest(String normalized) {
		if (normalized == null || normalized.isBlank())
			return false;
		String n = normalized.toLowerCase();
		return n.contains("throat") || n.contains("ear pain") || n.contains("earache") || n.contains("ear problem")
				|| n.contains("tooth") || n.contains("teeth") || n.contains("dental") || n.contains("heart")
				|| n.contains("chest pain");
	}

	/**
	 * Detect whether the user explicitly expressed a desire to cancel, using
	 * plain-text keywords, independent of LLM extraction. This keeps cancel
	 * behaviour grounded in what the caller actually said.
	 */
	private boolean containsExplicitCancelWord(String normalized) {
		if (normalized == null || normalized.isBlank())
			return false;
		String n = normalized.replace("'", ""); // handle "don't" / "dont"
		return n.contains("cancel") || n.contains("cut appointment") || n.contains("cut the appointment")
				|| n.contains("remove appointment") || n.contains("delete appointment")
				|| n.contains("dont need appointment") || n.contains("don't need appointment");
	}

	/**
	 * Heuristic: input is mostly non-ASCII characters or has no Latin letters ->
	 * likely foreign/unclear speech.
	 */
	private boolean isLikelyForeignOrUnclearText(String text) {
		if (text == null || text.isBlank())
			return false;
		int nonAscii = 0;
		int len = text.length();
		for (int i = 0; i < len; i++) {
			if (text.charAt(i) > 127)
				nonAscii++;
		}
		boolean noLatinLetters = !text.matches(".*[A-Za-z].*");
		return (nonAscii > 0 && nonAscii * 2 >= len) || noLatinLetters;
	}

	private boolean isPureThanks(String normalized) {
		String n = normalized.replaceAll("[!.]", "").trim();
		return n.equals("thank you") || n.equals("thanks") || n.equals("thank you so much") || n.equals("thanks a lot");
	}

	/**
	 * Map short YES/NO answers to the last assistant question, when no
	 * booking/cancel state is pending. Prevents "No"/"Nope" from accidentally being
	 * treated as a CANCEL intent on an existing appointment.
	 */
	private Optional<FlowResponse> handleShortYesNoWithoutPending(String callSid, YesNoResult yesNo,
			List<String> conversationSummary) {
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
				return Optional.of(FlowResponse.of(FlowResponse.Type.GOODBYE));
			}
			if (lastMsg.contains("end the call")) {
				clearPending(callSid);
				return Optional.of(FlowResponse.of(FlowResponse.Type.GOODBYE));
			}
			return Optional.of(FlowResponse.message("noProblemHowElse"));
		}

		if (yesNo == YesNoResult.YES) {
			if (lastMsg.contains("end the call")) {
				clearPending(callSid);
				return Optional.of(FlowResponse.of(FlowResponse.Type.GOODBYE));
			}
			return Optional.of(FlowResponse.message("sureWhatWouldYouLike"));
		}

		return Optional.empty();
	}

	private String buildUnknownDoctorResponse(String rawDoctorName) {
		List<Doctor> doctors = appointmentService.getAllDoctors();
		String names = doctors.stream().map(Doctor::getName).filter(Objects::nonNull).reduce((a, b) -> a + ", " + b)
				.orElse("");
		return "We don't have " + (rawDoctorName != null ? rawDoctorName.trim() : "that doctor") + ". We have " + names
				+ ". Which would you like?";
	}

	private String buildDoctorNotFoundMessage() {
		List<Doctor> doctors = appointmentService.getAllDoctors();
		if (doctors.isEmpty())
			return "We don't have any doctors available at the moment.";
		String names = doctors.stream().map(Doctor::getName).filter(n -> n != null && !n.isBlank())
				.reduce((a, b) -> a + ", " + b).orElse("");
		return "Doctor not found. We have " + names + ".";
	}

	private String normalizeTimeForSlot(String t) {
		if (t == null || t.isBlank())
			return null;
		t = t.trim();
		if (t.contains(" to "))
			t = t.substring(0, t.indexOf(" to ")).trim();

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
		if (t.matches("\\d{1,2}:\\d{2}\\s*(AM|PM)"))
			return t;
		if (t.matches("\\d{1,2}:\\d{2}")) {
			int h = Integer.parseInt(t.split(":")[0]);
			String m = t.split(":")[1];
			return (h >= 12 ? String.format("%d:%s PM", h == 12 ? 12 : h - 12, m)
					: String.format("%d:%s AM", h == 0 ? 12 : h, m));
		}
		if (t.matches("\\d{1,2}\\s*(AM|PM)"))
			return t.replaceFirst("(?i)(\\d{1,2})\\s*(AM|PM)", "$1:00 $2");
		if (t.matches("\\d{1,2}\\.\\d{2}")) {
			int h = Integer.parseInt(t.split("\\.")[0]);
			String m = t.split("\\.")[1];
			return (h >= 12 ? String.format("%d:%s PM", h == 12 ? 12 : h - 12, m)
					: String.format("%d:%s AM", h == 0 ? 12 : h, m));
		}
		if (t.matches("\\d{1,2}")) {
			int h = Integer.parseInt(t);
			if (h >= 1 && h <= 11)
				return String.format("%d:00 PM", h);
			if (h == 12)
				return "12:00 PM";
			if (h == 0)
				return "12:00 AM";
		}
		return t;
	}

	private String matchSlotTime(List<String> available, String userTime) {
		if (available == null)
			return null;
		String norm = normalizeTimeForSlot(userTime);
		if (norm == null)
			return null;
		String normComparable = toComparableTime(norm);
		for (String s : available) {
			if (s == null)
				continue;
			if (s.equalsIgnoreCase(norm))
				return s;
			if (toComparableTime(s).equals(normComparable))
				return s;
			String slotNorm = normalizeTimeForSlot(s);
			if (slotNorm != null && toComparableTime(slotNorm).equals(normComparable))
				return s;
		}
		return null;
	}

	private static String toComparableTime(String t) {
		if (t == null)
			return "";
		t = t.trim().toLowerCase().replace(" ", "");
		if (t.startsWith("0") && t.length() > 1 && Character.isDigit(t.charAt(1))) {
			t = t.replaceFirst("^0+(?=\\d)", "");
		}
		return t;
	}

	private ExtractedIntent extractIntent(String userText, List<String> conversationSummary, String openAiKey,
			String openAiModel) {
		ExtractedIntent out = new ExtractedIntent();
		out.intent = "none";

		if (StringUtils.isBlank(openAiKey))
			return out;

		String url = "https://api.openai.com/v1/chat/completions";
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(openAiKey);
		headers.setContentType(MediaType.APPLICATION_JSON);

		String context = conversationSummary != null && !conversationSummary.isEmpty() ? String.join("\n",
				conversationSummary.subList(Math.max(0, conversationSummary.size() - 6), conversationSummary.size()))
				: "";

		List<Doctor> doctors = appointmentService.getAllDoctors();
		String doctorList = doctors.stream()
				.map(d -> d.getKey() + " (" + d.getName()
						+ (d.getSpecialization() != null && !d.getSpecialization().isBlank()
								? ", " + d.getSpecialization()
								: "")
						+ ")")
				.reduce((a, b) -> a + ", " + b).orElse("");

		String todayIso = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
		String prompt = "Extract from conversation. Output JSON only. Today is " + todayIso + ".\n"
				+ "intent: book|cancel|reschedule|check_appointments|ask_availability|list_doctors|none\n"
				+ "is_general_question: true if the user's message is clearly NOT about appointments — e.g. weather, who is X (PM/president), greetings, farewell, thanks, general knowledge, current time as general question. false if about booking, cancelling, rescheduling, slots, doctors, or appointments.\n"
				+ "check_appointments: 'do I have an appointment', 'appointment confirmed', 'what are my appointments'.\n"
				+ "ask_availability: 'what dates/times available', 'available slots' — NOT when user asks to list doctors.\n"
				+ "list_doctors: 'list doctor names', 'what doctors are available', 'doctor names', 'list doctors'.\n"
				+ "doctorKey: Map user's doctor name to one of these keys from DB: [" + doctorList
				+ "]. Use context to resolve variants (e.g. Allen/Alan, John/Jon). For ask_availability, if AI just recommended a doctor and user says 'yes/sure/check slots', extract that doctor's key from the AI message.\n"
				+ "date: YYYY-MM-DD. Use today=" + todayIso + ", tomorrow="
				+ LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
				+ ". If AI offered slots for today/tomorrow and user accepts time, use that date.\n"
				+ "time: 12:00 PM, 12:30 PM, 01:00 PM etc. For '12 p.m.' use 12:00 PM. For '12pm' use 12:00 PM. For '6 to 7' use 06:00 PM.\n"
				+ "patientName, patientPhone: from user if given. For cancel/reschedule with multiple appointments, extract the name (e.g. 'cancel Selva's' -> patientName=Selva).\n"
				+ "If user only says time (e.g. '12 p.m. is fine'), keep doctor AND date from recent context (AI's offered slots).\n"
				+ "If user says 'to book tomorrow', output date=tomorrow.\n\n" + "Conversation:\n" + context
				+ "\n\nLast user: " + userText + "\n\nJSON:";

		Map<String, Object> body = new HashMap<>();
		body.put("model", openAiModel != null ? openAiModel : "gpt-4o-mini");
		body.put("temperature", 0);
		body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

		try {
			ResponseEntity<String> resp = restTemplate.postForEntity(url, new HttpEntity<>(body, headers),
					String.class);
			JsonNode root = mapper.readTree(resp.getBody());
			String content = root.path("choices").path(0).path("message").path("content").asText("");
			int start = content.indexOf('{');
			if (start >= 0) {
				JsonNode j = mapper.readTree(content.substring(start));
				out.intent = j.path("intent").asText("none");
				out.isGeneralQuestion = j.path("is_general_question").asBoolean(false);
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
		if (s == null || s.isBlank())
			return false;
		String digits = s.replaceAll("\\D", "");
		return digits.length() >= 6;
	}

	static class ExtractedIntent {
		String intent;
		boolean isGeneralQuestion;
		String doctorKey;
		String date;
		String time;
		String patientName;
		String patientPhone;
	}

	public Optional<FlowResponse> trySetPendingBook(String callSid, String fromNumber, String doctorKey, String date,
			String time, String name, String phone) {
		String d = normalizeDate(date);
		if (d == null)
			return Optional.empty();

		Map<String, Map<String, List<String>>> available = appointmentService.getAvailableSlotsForNextWeek();
		if (!available.containsKey(doctorKey))
			return Optional.empty();
		List<String> byDate = available.get(doctorKey).get(d);
		if (byDate == null || !byDate.contains(time))
			return Optional.empty();

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
		String docName = doctors.stream().filter(doc -> doc.getKey().equals(doctorKey)).findFirst()
				.map(doc -> doc.getName()).orElse(doctorKey);
		return Optional.of(FlowResponse.confirmBooking(docName, d, time));
	}

	private boolean isOtherDatesRequest(String text) {
	    if (text == null) return false;

	    String t = text.toLowerCase();

	    return t.contains("other date")
	            || t.contains("another date")
	            || t.contains("any other date")
	            || t.contains("different date")
	            || t.contains("next date");
	}


}
