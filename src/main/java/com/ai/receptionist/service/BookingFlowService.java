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

		Optional<String> selectionResponse = handleAppointmentSelection(callSid, userText);

		if (selectionResponse.isPresent())
			return selectionResponse;
		
		if (state != null && state.pendingCancel) {

			if (yesNo == YesNoResult.YES) {
				return confirmCancel(callSid, fromNumber, state.selectedPatientName);
			}

			if (yesNo == YesNoResult.NO) {
				clearPending(callSid);
				return Optional.of("Okay, your appointment remains scheduled.");
			}
		}
		
		if (state != null && state.pendingConfirmBook) {

			if (yesNo == YesNoResult.YES)
				return confirmBook(callSid, fromNumber, state);

			if (yesNo == YesNoResult.NO) {
				state.pendingConfirmBook = false;
				return Optional.of("Okay, would you like a different time?");
			}
		}

		if (state != null && state.pendingReschedule) {

			ExtractedIntent extracted = extractIntent(userText, conversationSummary, openAiKey, openAiModel);

			if (extracted.date != null && extracted.time != null) {

				state.rescheduleDate = extracted.date;
				state.rescheduleTime = extracted.time;
				state.rescheduleDoctorKey = extracted.doctorKey != null ? extracted.doctorKey : state.doctorKey;

				return confirmReschedule(callSid, fromNumber, state);
			}

			return Optional.of("What new date and time would you like?");
		}

		if (isLikelyForeignOrUnclearText(userText))
			return Optional.of(phrases.unclearAskAgain());
		ExtractedIntent extracted = extractIntent(userText, conversationSummary, openAiKey, openAiModel);

		log.info("[{}] intent={}", callSid, extracted.intent);

		if ("check_appointments".equals(extracted.intent)) {
			PendingStateDto s = getOrCreate(callSid);
			String phone = resolveCallerPhone(fromNumber, s);
			List<AppointmentService.AppointmentSummary> appts = appointmentService.getUpcomingAppointmentSummaries(phone);

			if (appts.isEmpty()) {
				return Optional.of("You don't have any upcoming appointments right now. Would you like to book one?");
			}

			if (appts.size() == 1) {
				AppointmentService.AppointmentSummary a = appts.get(0);
				return Optional.of("You have one upcoming appointment: " + a.patientName + " with " + a.doctorName
						+ " on " + a.slotDate + " at " + a.startTime + ". Is there anything else I can help with?");
			}

			StringBuilder msg = new StringBuilder("You have " + appts.size() + " upcoming appointments: ");
			for (int i = 0; i < appts.size(); i++) {
				AppointmentService.AppointmentSummary a = appts.get(i);
				if (i > 0) msg.append(", ");
				msg.append(i + 1).append(". ").append(a.patientName).append(" with ").append(a.doctorName)
						.append(" on ").append(a.slotDate).append(" at ").append(a.startTime);
			}
			msg.append(". Is there anything else I can help with?");
			return Optional.of(msg.toString());
		}

		/*
		 * ---------------------------------- CANCEL FLOW (MULTI-APPOINTMENT)
		 * ----------------------------------
		 */

		if ("cancel".equals(extracted.intent)) {
			return askWhichAppointment(callSid, fromNumber, true);
		}

		/*
		 * ---------------------------------- RESCHEDULE FLOW (MULTI-APPOINTMENT)
		 * ----------------------------------
		 */

		if ("reschedule".equals(extracted.intent)) {
			return askWhichAppointment(callSid, fromNumber, false);
		}

		/*
		 * ---------------------------------- AVAILABILITY
		 * ----------------------------------
		 */

		if ("ask_availability".equals(extracted.intent)) {

			// No doctorKey extracted — let the LLM handle it with full context
			if (extracted.doctorKey == null)
				return Optional.empty();

			Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();

			if (slots.isEmpty())
				return Optional.of("No slots available right now.");

			Map<String, List<String>> byDate = slots.get(extracted.doctorKey);

			if (byDate == null || byDate.isEmpty())
				return Optional.of("No availability found for that doctor.");

			String date = byDate.keySet().iterator().next();
			String time = byDate.get(date).get(0);

			PendingStateDto s = getOrCreate(callSid);
			s.lastSuggestedDoctorKey = extracted.doctorKey;
			s.lastSuggestedDate = date;
			s.lastSuggestedTime = time;

			List<Doctor> doctors = appointmentService.getAllDoctors();
			String docName = doctors.stream().filter(doc -> doc.getKey().equals(extracted.doctorKey)).findFirst()
					.map(Doctor::getName).orElse(extracted.doctorKey);

			return Optional.of("The nearest available slot for " + docName + " is " + date + " at " + time + ". Would that work?");
		}

		/*
		 * ---------------------------------- BOOK ----------------------------------
		 */

		if ("book".equals(extracted.intent) && extracted.doctorKey != null && extracted.date != null
				&& extracted.time != null) {

			return trySetPendingBook(callSid, fromNumber, extracted.doctorKey, extracted.date, extracted.time,
					extracted.patientName, extracted.patientPhone);
		}

		/*
		 * ---------------------------------- COLLECT NAME & PHONE
		 * ----------------------------------
		 */

		if (state != null && state.pendingNeedNamePhone) {

			if (extracted.patientName != null)
				state.patientName = extracted.patientName;

			if (extracted.patientPhone != null)
				state.patientPhone = extracted.patientPhone;

			if (state.patientName != null && state.patientPhone != null) {
				state.pendingNeedNamePhone = false;
				state.pendingConfirmBook = true;

				List<Doctor> doctors = appointmentService.getAllDoctors();
				String docName = doctors.stream().filter(doc -> doc.getKey().equals(state.doctorKey)).findFirst()
						.map(Doctor::getName).orElse(state.doctorKey);
				return Optional.of(phrases.confirmBookingPrompt(docName, state.date, state.time));
			}
		}

		if (state != null && state.hasAnyPending())
			return Optional.of("Sorry, could you repeat that?");

		return Optional.empty();
	}

	private Optional<String> confirmBook(String callSid, String fromNumber, PendingStateDto state) {

	    if (!state.pendingConfirmBook) {
	        throw new IllegalStateException("Booking attempted without confirmation.");
	    }

	    if (state.bookingLocked) {
	        return Optional.of(
	                "You're all set! Your appointment is confirmed for "
	                        + state.date + " at " + state.time
	                        + ". We'll see you then.");
	    }

	    if (StringUtils.isBlank(state.doctorKey)
	            || state.date == null
	            || StringUtils.isBlank(state.time)) {

	        clearPending(callSid);
	        return Optional.of("I don't have the full booking details. Let's try again.");
	    }

	    // ⭐ ALWAYS resolve caller identity
	    String twilioPhone = resolveCallerPhone(fromNumber, state);

	    log.info("Resolved caller phone: {}", twilioPhone);

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

	        return Optional.of(
	                "You're all set! Your appointment is confirmed for "
	                        + state.date + " at " + state.time
	                        + ". We'll see you then.");
	    }

	    return Optional.of(phrases.slotUnavailable());
	}

	private Optional<String> confirmCancel(String callSid, String fromNumber, String patientName) {

		PendingStateDto state = pendingByCall.get(callSid);
		String callerPhone = resolveCallerPhone(fromNumber, state);

		boolean ok = appointmentService.cancelAppointment(callerPhone, patientName);

		clearPending(callSid);

		if (ok) {
			return Optional.of(phrases.cancelConfirmed());
		}

		return Optional.of("I couldn't find an appointment to cancel.");
	}

	private String resolveCallerPhone(String fromNumber, PendingStateDto state) {

		String phone = fromNumber;

		boolean invalidCaller = phone == null || phone.isBlank() || phone.startsWith("client:")
				|| phone.equalsIgnoreCase("anonymous") || phone.equalsIgnoreCase("unknown");

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

		if (StringUtils.isBlank(state.rescheduleDoctorKey) || state.rescheduleDate == null
				|| StringUtils.isBlank(state.rescheduleTime)) {

			clearPending(callSid);
			return Optional.of("I don't have the new slot. Let's try again.");
		}

		String callerPhone = resolveCallerPhone(fromNumber, state);

		Optional<Appointment> result = appointmentService.rescheduleAppointment(callerPhone,
				state.reschedulePatientName, state.rescheduleDoctorKey, state.rescheduleDate, state.rescheduleTime);

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
		if (text == null)
			return false;

		String n = text.toLowerCase().replace("'", "");

		return n.contains("cancel process") || n.contains("cancel booking") || n.contains("cancel this")
				|| n.contains("stop booking") || n.contains("stop this") || n.contains("never mind")
				|| n.contains("forget it") || n.contains("leave it") || n.contains("abort") || n.equals("cancel")
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

	public Optional<String> trySetPendingBook(String callSid, String fromNumber, String doctorKey, String date,
			String time, String name, String phone) {

		log.info("[{}] trySetPendingBook doctor={} date={} time={}", callSid, doctorKey, date, time);

		String d = normalizeDate(date);
		if (d == null) {
			log.warn("[{}] Invalid date {}", callSid, date);
			return Optional.empty();
		}

		Map<String, Map<String, List<String>>> available = appointmentService.getAvailableSlotsForNextWeek();

		if (!available.containsKey(doctorKey)) {
			log.warn("[{}] Doctor not found {}", callSid, doctorKey);
			return Optional.empty();
		}

		List<String> times = available.get(doctorKey).get(d);

			if (times == null || !times.contains(time)) {
			log.warn("[{}] Time not available {} {}", callSid, d, time);
			// Find the nearest available slot for this doctor and suggest it
			Map<String, List<String>> byDate = available.get(doctorKey);
			if (byDate != null && !byDate.isEmpty()) {
				String nearestDate = byDate.keySet().iterator().next();
				String nearestTime = byDate.get(nearestDate).get(0);
				PendingStateDto s = getOrCreate(callSid);
				s.lastSuggestedDoctorKey = doctorKey;
				s.lastSuggestedDate = nearestDate;
				s.lastSuggestedTime = nearestTime;
				// Pre-fill booking state so user can confirm with just "yes"
				s.doctorKey = doctorKey;
				s.date = nearestDate;
				s.time = nearestTime;
				if (StringUtils.isNotBlank(name)) s.patientName = name;
				if (StringUtils.isNotBlank(phone)) s.patientPhone = phone;
				if (StringUtils.isBlank(s.patientName) || StringUtils.isBlank(s.patientPhone)) {
					s.pendingNeedNamePhone = true;
				}
				List<Doctor> doctors = appointmentService.getAllDoctors();
				String docName = doctors.stream().filter(doc -> doc.getKey().equals(doctorKey)).findFirst()
						.map(Doctor::getName).orElse(doctorKey);
				return Optional.of("That slot isn't available. The nearest open slot for " + docName + " is "
						+ nearestDate + " at " + nearestTime + ". Would that work?");
			}
			return Optional.of(phrases.slotUnavailable());
		}

		PendingStateDto s = getOrCreate(callSid);
		s.doctorKey = doctorKey;
		s.date = d;
		s.time = time;

		if (StringUtils.isNotBlank(name))
			s.patientName = name;

		if (StringUtils.isNotBlank(phone))
			s.patientPhone = phone;

		if (StringUtils.isBlank(s.patientName) || StringUtils.isBlank(s.patientPhone)) {

			s.pendingNeedNamePhone = true;
			s.pendingConfirmBook = false;

			log.info("[{}] Need patient name/phone before confirmation", callSid);

			if (StringUtils.isBlank(s.patientName) && StringUtils.isBlank(s.patientPhone)) {

				return Optional.of("May I have your name and contact number?");
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
		String docName = doctors.stream().filter(doc -> doc.getKey().equals(doctorKey)).findFirst().map(Doctor::getName)
				.orElse(doctorKey);

		return Optional.of(phrases.confirmBookingPrompt(docName, d, time));
	}

	private boolean isOtherDatesRequest(String text) {
		if (text == null)
			return false;

		String t = text.toLowerCase();

		return t.contains("other date") || t.contains("another date") || t.contains("any other date")
				|| t.contains("different date") || t.contains("next date");
	}

	private Optional<String> askWhichAppointment(String callSid, String fromNumber, boolean forCancel) {

		PendingStateDto state = getOrCreate(callSid);
		String phone = resolveCallerPhone(fromNumber, state);

		List<AppointmentService.AppointmentSummary> appts = appointmentService.getUpcomingAppointmentSummaries(phone);

		if (appts.isEmpty()) {
			return Optional.of("You don't have any upcoming appointments.");
		}

		if (appts.size() == 1) {
			AppointmentService.AppointmentSummary a = appts.get(0);

			if (forCancel) {
				state.pendingCancel = true;
			} else {
				state.pendingReschedule = true;
				state.rescheduleDoctorKey = a.doctorKey;
			}

			state.selectedPatientName = a.patientName;

			return Optional.of("You have an appointment for " + a.patientName + " with " + a.doctorName + " on "
					+ a.slotDate + " at " + a.startTime + ". Should I proceed?");
		}

		state.pendingAppointmentSelection = true;
		state.selectionForCancel = forCancel;
		state.selectionForReschedule = !forCancel;
		state.selectionList = appts;

		StringBuilder msg = new StringBuilder("I found these upcoming appointments: ");

		for (int i = 0; i < appts.size(); i++) {
			AppointmentService.AppointmentSummary a = appts.get(i);
			msg.append(i + 1).append(". ").append(a.patientName).append(" with ").append(a.doctorName).append(" on ")
					.append(a.slotDate).append(" at ").append(a.startTime);

			if (i < appts.size() - 1)
				msg.append(", ");
		}

		msg.append(". Which one would you like? You can say the number or the name.");

		return Optional.of(msg.toString());
	}

	private Optional<String> handleAppointmentSelection(String callSid, String userText) {

		PendingStateDto state = getOrCreate(callSid);

		if (!state.pendingAppointmentSelection || state.selectionList == null)
			return Optional.empty();

		String text = userText.toLowerCase();

		AppointmentService.AppointmentSummary selected = null;

		// check number selection
		for (int i = 0; i < state.selectionList.size(); i++) {
			if (text.contains(String.valueOf(i + 1))) {
				selected = state.selectionList.get(i);
				break;
			}
		}

		// check name selection
		if (selected == null) {
			for (AppointmentService.AppointmentSummary a : state.selectionList) {
				if (text.contains(a.patientName.toLowerCase())) {
					selected = a;
					break;
				}
			}
		}

		if (selected == null) {
			return Optional.of("Sorry, I didn't catch which appointment. Please say the number or the patient name.");
		}

		state.selectedPatientName = selected.patientName;
		state.pendingAppointmentSelection = false;

		if (state.selectionForCancel) {
			state.pendingCancel = true;
			return Optional.of("Do you want to cancel the appointment for " + selected.patientName + "?");
		}

		if (state.selectionForReschedule) {
			state.pendingReschedule = true;
			state.rescheduleDoctorKey = selected.doctorKey;
			return Optional.of("What new date and time would you like?");
		}

		return Optional.empty();
	}

}
