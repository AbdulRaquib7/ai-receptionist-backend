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
			YesNoClassifierService yesNoClassifier, ResponsePhrases phrases, IntentClassifier intentClassifier,
			IntentPriorityResolverService intentPriorityResolver) {
		this.appointmentService = appointmentService;
		this.restTemplate = builder.build();
		this.yesNoClassifier = yesNoClassifier;
		this.phrases = phrases;
		this.intentClassifier = intentClassifier;
		this.intentPriorityResolver = intentPriorityResolver;
	}

	public Optional<String> processUserMessage(String callSid, String fromNumber, String userText,
			List<String> conversationSummary, String openAiKey, String openAiModel) {

		if (StringUtils.isBlank(userText))
			return Optional.empty();

		userText = normalizeAppointmentTypo(userText);
		String normalized = userText.toLowerCase().trim();

		PendingStateDto state = pendingByCall.get(callSid);

		log.info("[{}] incoming='{}'", callSid, userText);
		
		if (state != null && isAbortBookingRequest(normalized)) {
			clearPending(callSid);
			return Optional.of("No problem. I've stopped the booking.");
		}

		if (wantsToEndCall(normalized)) {
			clearPending(callSid);
			return Optional.of(phrases.goodbye());
		}

		YesNoResult yesNo = yesNoClassifier.classify(userText);

		if (state != null && state.pendingCancel) {

			if (yesNo == YesNoResult.YES) {
				return confirmCancel(callSid, fromNumber, state.cancelPatientName);
			}

			if (yesNo == YesNoResult.NO) {
				clearPending(callSid);
				return Optional.of("Okay, your appointment remains scheduled.");
			}
		}

		if (state != null && state.pendingConfirmBook) {

			if (yesNo == YesNoResult.YES) {
				return confirmBook(callSid, fromNumber, state);
			}

			if (yesNo == YesNoResult.NO) {
				state.pendingConfirmBook = false;
				return Optional.of("Okay, no problem. Would you like a different time?");
			}
		}

		if (state != null && isOtherDatesRequest(normalized) && state.lastSuggestedDoctorKey != null) {

			Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();

			Map<String, List<String>> byDate = slots.get(state.lastSuggestedDoctorKey);

			if (byDate == null || byDate.isEmpty())
				return Optional.of("No other dates available.");

			List<String> dates = new ArrayList<>(byDate.keySet());
			dates.sort(Comparator.comparing(LocalDate::parse));

			dates.remove(state.lastSuggestedDate);

			if (dates.isEmpty())
				return Optional.of("That's the only available date.");

			return Optional.of("We also have " + String.join(" and ", dates) + ". Which works for you?");
		}
		
		if (isLikelyForeignOrUnclearText(userText)) {
			return Optional.of(phrases.unclearAskAgain());
		}

		ExtractedIntent extracted = extractIntent(userText, conversationSummary, openAiKey, openAiModel);

		log.info("[{}] intent={} doctor={} date={} time={}", callSid, extracted.intent, extracted.doctorKey,
				extracted.date, extracted.time);

		if (state != null && state.pendingReschedule && extracted.date != null && extracted.time != null) {

			state.rescheduleDate = extracted.date;
			state.rescheduleTime = extracted.time;
			state.rescheduleDoctorKey = extracted.doctorKey != null ? extracted.doctorKey : state.doctorKey;

			return confirmReschedule(callSid, fromNumber, state);
		}

		if (state != null && state.pendingReschedule && (extracted.date == null || extracted.time == null)) {

			return Optional.of("What new date and time would you like?");
		}
		
		if ("cancel".equals(extracted.intent)) {

			PendingStateDto s = getOrCreate(callSid);
			s.pendingCancel = true;

			if (extracted.patientName != null) {
				s.cancelPatientName = extracted.patientName;
			}

			state = s; // refresh state

			return Optional.of("Are you sure you want to cancel your appointment? Please say yes or no.");
		}

		if ("reschedule".equals(extracted.intent)) {

			PendingStateDto s = getOrCreate(callSid);
			s.pendingReschedule = true;
			s.reschedulePatientName = extracted.patientName;

			state = s;

			return Optional.of("Sure. What new date and time would you prefer?");
		}

		if ("ask_availability".equals(extracted.intent)) {

			Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();

			if (slots.isEmpty())
				return Optional.of("No slots available right now.");

			String doctorKey = extracted.doctorKey;

			if (doctorKey == null)
				return Optional.of("Which doctor would you like me to check?");

			Map<String, List<String>> byDate = slots.get(doctorKey);

			if (byDate == null || byDate.isEmpty())
				return Optional.of("No availability found.");

			String firstDate = byDate.keySet().iterator().next();
			String firstTime = byDate.get(firstDate).get(0);

			PendingStateDto s = getOrCreate(callSid);
			s.lastSuggestedDoctorKey = doctorKey;
			s.lastSuggestedDate = firstDate;

			return Optional
					.of("The nearest available slot is " + firstDate + " at " + firstTime + ". Would that work?");
		}
		
		if ("book".equals(extracted.intent) && extracted.doctorKey != null && extracted.date != null
				&& extracted.time != null) {

			return trySetPendingBook(callSid, fromNumber, extracted.doctorKey, extracted.date, extracted.time,
					extracted.patientName, extracted.patientPhone);
		}

		if (state != null && state.pendingNeedNamePhone) {

			if (extracted.patientName != null)
				state.patientName = extracted.patientName;

			if (extracted.patientPhone != null)
				state.patientPhone = extracted.patientPhone;

			if (state.patientName != null && state.patientPhone != null) {
				state.pendingNeedNamePhone = false;
				state.pendingConfirmBook = true;

				return Optional.of(phrases.confirmBookingPrompt(state.doctorKey, state.date, state.time));
			}
		}

		if (state != null && state.hasAnyPending()) {
			return Optional.of("Sorry, could you repeat that?");
		}

		return Optional.empty();
	}

	private Optional<String> confirmBook(String callSid, String fromNumber, PendingStateDto state) {

	    if (!state.pendingConfirmBook) {
	        throw new IllegalStateException("Booking attempted without confirmation.");
	    }

	    if (state.bookingLocked) {
	        return Optional.of("You're all set! Your appointment is confirmed for "
	                + state.date + " at " + state.time
	                + ". We'll see you then. Take care!");
	    }

	    if (StringUtils.isBlank(state.doctorKey)
	            || state.date == null
	            || StringUtils.isBlank(state.time)) {

	        clearPending(callSid);
	        return Optional.of("I don't have the full booking details. Let's try again.");
	    }

	    String twilioPhone = fromNumber;

	    boolean invalidCaller =
	            twilioPhone == null
	            || twilioPhone.isBlank()
	            || twilioPhone.startsWith("client:")
	            || twilioPhone.equalsIgnoreCase("anonymous")
	            || twilioPhone.equalsIgnoreCase("unknown");

	    if (invalidCaller) {

	        log.warn("[{}] Twilio test call detected. No real caller number.", callSid);

	        if (StringUtils.isNotBlank(state.patientPhone)) {
	            twilioPhone = state.patientPhone;
	        } else {
	            twilioPhone = "+10000000000";
	        }
	    }
	    log.info("tiwlio phone:{}",twilioPhone);

	    Optional<Appointment> result =
	            appointmentService.bookAppointment(
	                    twilioPhone,
	                    state.patientName,
	                    state.patientPhone,
	                    state.doctorKey,
	                    state.date,
	                    state.time);

	    if (result.isPresent()) {
	        state.bookingLocked = true;
	        state.bookingCompleted = true;
	        state.currentState = ConversationState.COMPLETED;

	        log.info("Booked appointment for callSid={} doctor={} date={} time={}",
	                callSid, state.doctorKey, state.date, state.time);

	        return Optional.of("You're all set! Your appointment is confirmed for "
	                + state.date + " at " + state.time
	                + ". We'll see you then. Take care!");
	    }

	    return Optional.of(phrases.slotUnavailable());
	}

	private Optional<String> confirmCancel(String callSid, String fromNumber, String patientName) {

	    String callerPhone = resolveCallerPhone(fromNumber, null);

	    boolean ok = appointmentService.cancelAppointment(callerPhone, patientName);

	    clearPending(callSid);

	    if (ok) {
	        return Optional.of(phrases.cancelConfirmed());
	    }

	    return Optional.of("I couldn't find an appointment to cancel.");
	}
	
	private String resolveCallerPhone(String fromNumber, PendingStateDto state) {

	    String phone = fromNumber;

	    boolean invalidCaller =
	            phone == null ||
	            phone.isBlank() ||
	            phone.startsWith("client:") ||
	            phone.equalsIgnoreCase("anonymous") ||
	            phone.equalsIgnoreCase("unknown");

	    if (invalidCaller) {

	        log.warn("Twilio test caller detected");

	        if (state != null && StringUtils.isNotBlank(state.patientPhone)) {
	            return state.patientPhone;
	        }

	        return "+10000000000"; 
	    }

	    return phone;
	}

	private Optional<String> confirmReschedule(String callSid, String fromNumber, PendingStateDto state) {

	    if (StringUtils.isBlank(state.rescheduleDoctorKey)
	            || state.rescheduleDate == null
	            || StringUtils.isBlank(state.rescheduleTime)) {

	        clearPending(callSid);
	        return Optional.of("I don't have the new slot. Let's try again.");
	    }

	    String callerPhone = resolveCallerPhone(fromNumber, state);

	    Optional<Appointment> result =
	            appointmentService.rescheduleAppointment(
	                    callerPhone,
	                    state.reschedulePatientName,
	                    state.rescheduleDoctorKey,
	                    state.rescheduleDate,
	                    state.rescheduleTime);

	    clearPending(callSid);

	    if (result.isPresent()) {
	        return Optional.of(phrases.rescheduleConfirmed());
	    }

	    return Optional.of("I couldn't find your existing appointment. Would you like to book a new one?");
	}

	private boolean wantsToEndCall(String normalized) {
		if (normalized == null)
			return false;

		String n = normalized.toLowerCase();

		return n.contains("end call") || n.contains("hang up") || n.contains("hangup") || n.contains("goodbye")
				|| n.contains("bye") || n.contains("that's all") || n.contains("nothing else") || n.contains("ok bye")
				|| n.contains("thank you bye") || n.equals("bye") || n.equals("goodbye");
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
		if (d == null)
			return null;

		d = d.toLowerCase().replaceAll("(yeah|i need|i want|for|on|about)", "").trim();

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
	
	private String normalizeAppointmentTypo(String userText) {
		if (userText == null || userText.isEmpty())
			return userText;
		return userText.replaceAll("(?i)\\bapartment\\b", "appointment");
	}
	
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
	
	static class ExtractedIntent {
		String intent;
		boolean isGeneralQuestion;
		String doctorKey;
		String date;
		String time;
		String patientName;
		String patientPhone;
	}

	public Optional<String> trySetPendingBook(
	        String callSid,
	        String fromNumber,
	        String doctorKey,
	        String date,
	        String time,
	        String name,
	        String phone) {

	    log.info("[{}] trySetPendingBook doctor={} date={} time={}",
	            callSid, doctorKey, date, time);

	    String d = normalizeDate(date);
	    if (d == null) {
	        log.warn("[{}] Invalid date {}", callSid, date);
	        return Optional.empty();
	    }

	    Map<String, Map<String, List<String>>> available =
	            appointmentService.getAvailableSlotsForNextWeek();

	    if (!available.containsKey(doctorKey)) {
	        log.warn("[{}] Doctor not found {}", callSid, doctorKey);
	        return Optional.empty();
	    }

	    List<String> times = available.get(doctorKey).get(d);

	    if (times == null || !times.contains(time)) {
	        log.warn("[{}] Time not available {} {}", callSid, d, time);
	        return Optional.empty();
	    }

	    PendingStateDto s = getOrCreate(callSid);
	    s.doctorKey = doctorKey;
	    s.date = d;
	    s.time = time;

	    if (StringUtils.isNotBlank(name))
	        s.patientName = name;

	    if (StringUtils.isNotBlank(phone))
	        s.patientPhone = phone;

	    if (StringUtils.isBlank(s.patientName)
	            || StringUtils.isBlank(s.patientPhone)) {

	        s.pendingNeedNamePhone = true;
	        s.pendingConfirmBook = false;

	        log.info("[{}] Need patient name/phone before confirmation", callSid);

	        if (StringUtils.isBlank(s.patientName)
	                && StringUtils.isBlank(s.patientPhone)) {

	            return Optional.of(
	                    "May I have your name and contact number?");
	        }

	        if (StringUtils.isBlank(s.patientName)) {
	            return Optional.of("May I have your name?");
	        }

	        return Optional.of("May I have your contact number?");
	    }

	    s.pendingNeedNamePhone = false;
	    s.pendingConfirmBook = true;

	    log.info("[{}] Pending confirmation ready", callSid);

	    List<Doctor> doctors = appointmentService.getAllDoctors();
	    String docName = doctors.stream()
	            .filter(doc -> doc.getKey().equals(doctorKey))
	            .findFirst()
	            .map(Doctor::getName)
	            .orElse(doctorKey);

	    return Optional.of(
	            phrases.confirmBookingPrompt(docName, d, time));
	}

	private boolean isOtherDatesRequest(String text) {
		if (text == null)
			return false;

		String t = text.toLowerCase();

		return t.contains("other date") || t.contains("another date") || t.contains("any other date")
				|| t.contains("different date") || t.contains("next date");
	}

}
