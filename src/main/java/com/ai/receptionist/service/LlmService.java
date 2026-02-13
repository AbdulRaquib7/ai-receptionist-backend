package com.ai.receptionist.service;

import com.ai.receptionist.entity.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.ai.receptionist.entity.Doctor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates replies using OpenAI Chat Completions.
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppointmentService appointmentService;

    @Value("${openai.api-key}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String openAiModel;

    public LlmService(RestTemplateBuilder builder, AppointmentService appointmentService) {
        this.restTemplate = builder.build();
        this.appointmentService = appointmentService;
    }

    public String generateReply(String callSid, String fromNumber, List<ChatMessage> history) {
        if (StringUtils.isBlank(openAiApiKey)) {
            log.error("OPENAI_API_KEY is not set");
            return "";
        }

        String url = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openAiApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        StringBuilder context = new StringBuilder();
        context.append("DATABASE STATE (fetch dynamically - this is the single source of truth):\n\n");

        List<Doctor> doctors = appointmentService.getAllDoctors();
        context.append("DOCTORS (from doctor table):\n");
        for (Doctor d : doctors) {
            context.append("- key: ").append(d.getKey())
                    .append(", name: ").append(d.getName());
            if (d.getSpecialization() != null && !d.getSpecialization().isBlank()) {
                context.append(", specialization: ").append(d.getSpecialization());
            }
            context.append("\n");
        }

        Map<String, Map<String, List<String>>> slots = appointmentService.getAvailableSlotsForNextWeek();
        context.append("\nAVAILABLE SLOTS (from appointment_slot where status=AVAILABLE). Format as ranges when consecutive: e.g. 6,7,8,9 PM -> '6 to 9 PM' or '6-9 PM'.\n");
        slots.forEach((docKey, byDate) -> {
            context.append(docKey).append(": ");
            List<String> rangeLines = new ArrayList<>();
            byDate.forEach((date, times) -> {
                if (times != null && !times.isEmpty()) {
                    rangeLines.add(date + " " + formatSlotsAsRanges(times));
                }
            });
            context.append(String.join("; ", rangeLines)).append("\n");
        });

        List<AppointmentService.AppointmentSummary> appointments = StringUtils.isNotBlank(fromNumber)
                ? appointmentService.getActiveAppointmentSummaries(fromNumber)
                : List.of();
        if (!appointments.isEmpty()) {
            context.append("\nCALLER'S APPOINTMENT(S): ");
            for (int i = 0; i < appointments.size(); i++) {
                AppointmentService.AppointmentSummary a = appointments.get(i);
                if (i > 0) context.append(" | ");
                context.append(a.patientName).append(" with ").append(a.doctorName)
                        .append(" on ").append(a.slotDate).append(" at ").append(a.startTime);
            }
            context.append("\n");
        } else {
            context.append("\nCALLER HAS NO EXISTING APPOINTMENT.\n");
        }

        context.append("\nVOICE & PERSONALITY - YOU ARE A REAL HUMAN RECEPTIONIST:\n");
        context.append("- Speak casually but professionally. Friendly, warm, conversational.\n");
        context.append("- Use natural phrases: \"Hey!\", \"Sure!\", \"No worries.\", \"Alright.\", \"Got it.\", \"Okay cool.\", \"Sounds good.\", \"Perfect.\"\n");
        context.append("- NEVER say: \"How may I assist you today?\", \"Please provide the required information.\", \"As an AI\", \"I am a language model\".\n");
        context.append("- Short sentences. Natural pauses. 1-2 sentences max for voice. Vary your phrasing — never repeat the exact same sentence twice in a row.\n");
        context.append("- Vary responses: \"Which doctor?\" vs \"Who would you like to see?\" vs \"And which doctor works for you?\"\n");
        context.append("\nGENERAL QUESTIONS & INTERRUPTIONS:\n");
        context.append("- If user asks off-topic (weather, time, doctor types, \"how are you\", \"what time do you close\"): answer briefly, then smoothly return: \"Now — you were picking a time. Morning or evening works better?\" or \"What can I help you with for appointments?\"\n");
        context.append("- Remember context. After answering, bring them back to where they were.\n");
        context.append("\nDOCTOR INFO (from DB above):\n");
        context.append("- When listing doctors, use specializations from DB. Example: \"We've got general physicians, cardiologists for heart stuff, dentists... What kind of issue are you dealing with?\"\n");
        context.append("- If user describes symptoms (e.g. chest pain): suggest matching specialization, then offer slots. \"Chest pain can be serious — I'd recommend our cardiologist. Want me to check available slots?\"\n");
        context.append("\nFLOWS (all data from above):\n");
        context.append("- BOOK: Ask specialization/problem → suggest doctor → date → 2-3 time slots (use ranges like 6-9 PM) → name & phone → confirm.\n");
        context.append("- LIST DOCTORS: When user asks \"list doctor names\" or \"what doctors\" — list from DB with specializations, then \"Which would you like to book with?\"\n");
        context.append("- FLOW RESUME: If user asks unrelated question (e.g. \"what doctors are available?\") during booking — answer briefly, then \"Now, about your appointment — what symptoms are you experiencing?\" or return to where you were.\n");
        context.append("- DOCTOR CONTEXT: Never return slots for a different doctor. If user switches doctor, acknowledge: \"Sure, switching to Dr X.\"\n");
        context.append("- RESCHEDULE: Fetch by caller → confirm existing → new date/time → confirm.\n");
        context.append("- CANCEL: Fetch by caller → confirm → if yes cancel, if no ask \"stop or start over?\".\n");
        context.append("- YES/NO: Always wait for explicit yes/no. Never proceed without confirmation.\n");
        context.append("- INTERRUPT DURING CONFIRM: If user says \"Yes confirm, but before that tell me about Dr X\" — provide doctor info first, then ask \"Would you like me to confirm the appointment now?\"\n");
        context.append("- If user says bye/goodbye: \"Thanks for calling. Take care!\"\n");
        context.append("- If user says \"I'll call later\": \"Sure! No worries. We'll be here. Have a good day!\"\n");
        context.append("- If slot unavailable: \"That one's taken. Want to try a different time?\"\n");
        context.append("- UNCLEAR AUDIO: If user message is garbled, doesn't fit context, or sounds like a mishear (e.g. wrong doctor name, nonsensical answer), ask them to repeat. Vary: \"Sorry, I didn't catch that. Could you repeat?\" or \"The line was a bit unclear. Could you say that again?\" or \"Sorry, what was that?\"\n");
        context.append("- GENERAL QUESTIONS: If user asks something off-topic (e.g. \"what's the weather\", \"how are you\", \"tell me about X\" unrelated to doctors/appointments), answer briefly in 1 sentence, then smoothly return to appointment flow.\n");

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", context.toString());
        messages.add(systemMsg);

        for (ChatMessage msg : history) {
            Map<String, String> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            messages.add(m);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", openAiModel);
        body.put("temperature", 0.2);
        body.put("messages", messages);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            JsonNode root = mapper.readTree(response.getBody());
            return root.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (Exception ex) {
            log.error("Failed to get LLM reply", ex);
            return "";
        }
    }

    /**
     * Format slot times as human-readable ranges. E.g. [06:00 PM, 07:00 PM, 08:00 PM, 09:00 PM] -> "6 to 9 PM".
     * Consecutive half-hour slots are grouped.
     */
    public static String formatSlotsAsRanges(List<String> times) {
        if (times == null || times.isEmpty()) return "";
        List<Integer> minutesFromMidnight = new ArrayList<>();
        for (String t : times) {
            Integer m = parseTimeToMinutes(t);
            if (m != null) minutesFromMidnight.add(m);
        }
        if (minutesFromMidnight.isEmpty()) return times.toString();
        minutesFromMidnight.sort(Integer::compareTo);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < minutesFromMidnight.size()) {
            int start = minutesFromMidnight.get(i);
            int end = start;
            while (i + 1 < minutesFromMidnight.size() && minutesFromMidnight.get(i + 1) <= end + 35) {
                i++;
                end = minutesFromMidnight.get(i);
            }
            if (sb.length() > 0) sb.append(", ");
            if (start == end) {
                sb.append(minutesToDisplay(start));
            } else {
                sb.append(minutesToDisplay(start)).append(" to ").append(minutesToDisplay(end));
            }
            i++;
        }
        return sb.toString();
    }

    private static Integer parseTimeToMinutes(String t) {
        if (t == null || t.isBlank()) return null;
        t = t.trim();
        boolean pm = t.toLowerCase().contains("pm") && !t.toLowerCase().contains("12:00 am");
        boolean am = t.toLowerCase().contains("am");
        String[] parts = t.replaceAll("(?i)(am|pm)", "").trim().split("[:.]");
        if (parts.length < 1) return null;
        int h = 0, m = 0;
        try {
            h = Integer.parseInt(parts[0].trim());
            if (parts.length >= 2) m = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (pm && h != 12) h += 12;
        if (am && h == 12) h = 0;
        return h * 60 + m;
    }

    private static String minutesToDisplay(int mins) {
        int h = mins / 60;
        int m = mins % 60;
        if (h >= 12) {
            if (h > 12) h -= 12;
            return m > 0 ? String.format("%d:%02d PM", h, m) : String.format("%d PM", h);
        }
        if (h == 0) h = 12;
        return m > 0 ? String.format("%d:%02d AM", h, m) : String.format("%d AM", h);
    }
}
