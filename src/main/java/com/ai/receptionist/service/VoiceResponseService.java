package com.ai.receptionist.service;

import com.ai.receptionist.component.ResponsePhrases;
import com.ai.receptionist.dto.FlowResponse;
import org.springframework.stereotype.Service;

/**
 * Converts structured FlowResponse into short, voice-friendly speech.
 * No flow logic — only type + payload → human-friendly sentence(s).
 */
@Service
public class VoiceResponseService {

    private final ResponsePhrases phrases;

    public VoiceResponseService(ResponsePhrases phrases) {
        this.phrases = phrases;
    }

    public String toSpeech(FlowResponse response) {
        if (response == null)
            return "";
        switch (response.getType()) {
            case ASK_SYMPTOM:
                return phrases.clarifyWhatHappened();
            case ASK_NAME:
            case ASK_PHONE:
                return phrases.needNameAndPhone();
            case SUGGEST_DOCTOR:
                return toSpeechSuggestDoctor(response);
            case SUGGEST_SLOT:
                return toSpeechSuggestSlot(response);
            case CONFIRM_BOOKING:
                return phrases.confirmBookingPrompt(
                        response.getString("doctorName"),
                        response.getString("date"),
                        response.getString("time"));
            case CONFIRMED:
                String cDate = response.getString("date");
                String cTime = response.getString("time");
                if (cDate != null && cTime != null)
                    return phrases.bookingConfirmedWithDetails(cDate, cTime);
                return phrases.bookingConfirmed();
            case CANCELLED:
                return phrases.cancelConfirmed();
            case RESCHEDULED:
                return phrases.rescheduleConfirmed();
            case REPEAT:
                return phrases.couldYouRepeat();
            case GOODBYE:
                return phrases.goodbye();
            case CLARIFY:
                return phrases.clarifyWhatHappened();
            case SLOT_UNAVAILABLE:
                return phrases.slotUnavailable();
            case NO_APPOINTMENTS:
                return phrases.noAppointmentsToCancel();
            case ABORT_BOOKING:
                return phrases.abortBookingAck();
            case RETURN_TO_FLOW:
                return phrases.continueBooking();
            case OFFER_OTHER_DATES:
                return phrases.weAlsoHave(response.getString("dates"));
            case MESSAGE:
                return messageFromKey(response.getString("messageKey"));
            case NONE:
            default:
                return "";
        }
    }

    private String toSpeechSuggestDoctor(FlowResponse response) {
        String doctorList = response.getString("doctorList");
        if (doctorList != null && !doctorList.isEmpty())
            return doctorList;
        String doctorName = response.getString("doctorName");
        String spec = response.getString("specialization");
        if (doctorName != null)
            return doctorName + " is our " + (spec != null ? spec : "doctor") + ". " + phrases.needNameAndPhone().toLowerCase();
        return phrases.needNameAndPhone();
    }

    private String toSpeechSuggestSlot(FlowResponse response) {
        String doctorName = response.getString("doctorName");
        String date = response.getString("date");
        String time = response.getString("time");
        if (date != null && time != null) {
            if (doctorName != null && !doctorName.isEmpty())
                return phrases.doctorIntroThenSlot(doctorName, "doctor", date, time);
            return phrases.nearestSlotIs(date, time);
        }
        return phrases.nearestSlotIs("?", "?");
    }

    private String messageFromKey(String key) {
        if (key == null) return "";
        switch (key) {
            case "noOtherDates":
                return phrases.noOtherDates();
            case "noSlots":
                return phrases.noSlots();
            case "whichDoctor":
                return phrases.whichDoctor();
            case "doctorNoAvailability":
                return phrases.doctorNoAvailability();
            case "noAvailableSlotsFound":
                return phrases.noAvailableSlotsFound();
            case "differentTime":
                return phrases.differentTime();
            case "tryAgainDetails":
                return phrases.tryAgainDetails();
            case "couldntCancel":
                return phrases.couldntCancel();
            case "tryAgainNewSlot":
                return phrases.tryAgainNewSlot();
            case "noProblemHowElse":
                return phrases.noProblemHowElse();
            case "sureWhatWouldYouLike":
                return phrases.sureWhatWouldYouLike();
            case "unclearAskAgain":
                return phrases.unclearAskAgain();
            case "noDoctorsAvailable":
                return phrases.noDoctorsAvailable();
            default:
                return "";
        }
    }
}
