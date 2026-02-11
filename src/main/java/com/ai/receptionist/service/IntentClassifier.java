package com.ai.receptionist.service;

import com.ai.receptionist.conversation.ConversationIntent;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Disambiguates user intent. ASK_DOCTOR_INFO must never trigger slot fetch or booking.
 */
@Service
public class IntentClassifier {

    private static final Pattern ASK_DOCTOR_INFO = Pattern.compile(
            "\\b(tell me about|tell about|about (dr\\.?|doctor)|what('s| is) (dr\\.?|doctor)|know about|before that|before (i |we )?confirm|describe|info about)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CONFIRM_YES = Pattern.compile(
            "\\b(yes|yeah|yep|yup|ok|okay|sure|confirm|correct|right|absolutely|definitely)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CONFIRM_NO = Pattern.compile(
            "\\b(no|nope|nah|cancel|never mind|don't|wait|not now)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern REQUEST_SLOTS = Pattern.compile(
            "\\b(slots?|availability|available|times?|dates?|schedule|when (is|are)|check (slots?|availability))\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BOOK = Pattern.compile(
            "\\b(book|appointment|schedule an?|want (an? |to )?appointment)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CANCEL = Pattern.compile(
            "\\b(cancel|delete|remove|drop)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern RESCHEDULE = Pattern.compile(
            "\\b(reschedule|change (date|time)|move (it|appointment)|postpone)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CHECK_APPOINTMENTS = Pattern.compile(
            "\\b(do i have|my appointment|any appointment|list (my )?appointment|what('s| are) my)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Classify intent. Interrupt phrases (ask doctor info during confirm) take precedence.
     */
    public ConversationIntent classify(String userText, boolean awaitingBookingConfirmation) {
        if (userText == null || userText.isBlank()) return ConversationIntent.NONE;
        String t = userText.trim();

        if (awaitingBookingConfirmation && ASK_DOCTOR_INFO.matcher(t).find()) {
            return ConversationIntent.ASK_DOCTOR_INFO;
        }

        if (ASK_DOCTOR_INFO.matcher(t).find() && !REQUEST_SLOTS.matcher(t).find()) {
            return ConversationIntent.ASK_DOCTOR_INFO;
        }

        if (CHECK_APPOINTMENTS.matcher(t).find()) {
            return ConversationIntent.CHECK_APPOINTMENTS;
        }

        if (CANCEL.matcher(t).find() && !RESCHEDULE.matcher(t).find()) {
            return ConversationIntent.CANCEL_APPOINTMENT;
        }
        if (RESCHEDULE.matcher(t).find()) {
            return ConversationIntent.RESCHEDULE_APPOINTMENT;
        }

        if (awaitingBookingConfirmation) {
            if (CONFIRM_NO.matcher(t).find()) return ConversationIntent.CONFIRM_NO;
            if (CONFIRM_YES.matcher(t).find() && !ASK_DOCTOR_INFO.matcher(t).find()) {
                return ConversationIntent.CONFIRM_YES;
            }
        }

        if (REQUEST_SLOTS.matcher(t).find()) {
            return ConversationIntent.REQUEST_SLOTS;
        }

        if (BOOK.matcher(t).find()) {
            return ConversationIntent.BOOK_APPOINTMENT;
        }

        return ConversationIntent.NONE;
    }

    public boolean isAskDoctorInfo(String userText) {
        return classify(userText, false) == ConversationIntent.ASK_DOCTOR_INFO;
    }

    public boolean isInterruptRequestingDoctorInfo(String userText, boolean awaitingConfirm) {
        return awaitingConfirm && ASK_DOCTOR_INFO.matcher(userText != null ? userText : "").find();
    }
}
