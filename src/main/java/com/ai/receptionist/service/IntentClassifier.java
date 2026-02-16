package com.ai.receptionist.service;

import com.ai.receptionist.conversation.ConversationIntent;
import com.ai.receptionist.conversation.IntentResult;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Multi-intent classifier. Detects all intents in user text; supports conflict resolution
 * (e.g. CONFIRM_YES + ASK_DOCTOR_INFO → ASK_DOCTOR_INFO takes precedence).
 */
@Service
public class IntentClassifier {

    private static final Pattern ASK_DOCTOR_INFO = Pattern.compile(
            "\\b(tell me about|tell about|explain about|explain|about (dr\\.?|doctor)|what('s| is) (dr\\.?|doctor)|know about|how (he|she|they) (was|is)|whole experience|before that|before (i |we )?confirm|describe|info about|can you (tell|explain) about)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Excludes CONFIRM_YES when user means "verify/explain doctor" or "before I book", not "confirm the booking". */
    private static final Pattern CONFIRM_YES_EXCLUDE = Pattern.compile(
            "\\b(confirm the doctor|confirm who|confirm (which|what) doctor|before (i |we )?book|before booking)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CONFIRM_YES = Pattern.compile(
            "\\b(yes|yeah|yep|yup|ok|okay|sure|confirm|confirmed|correct|right|absolutely|definitely)\\b",
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

    private static final Pattern CHANGE_DOCTOR = Pattern.compile(
            "\\b(different doctor|another doctor|switch|change doctor|other doctor)\\b",
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

    private static final Pattern PROVIDE_DETAILS = Pattern.compile(
            "\\b(name is|phone is|number is|my name|call me|i'm |i am |\\d{3}[-. ]?\\d{3}[-. ]?\\d{4})\\b",
            Pattern.CASE_INSENSITIVE
    );

    public IntentResult classifyMulti(String userText, boolean awaitingBookingConfirmation) {
        if (userText == null || userText.isBlank()) return IntentResult.empty();
        String t = userText.trim();
        Set<ConversationIntent> intents = EnumSet.noneOf(ConversationIntent.class);

        boolean hasAskDoctorInfo = ASK_DOCTOR_INFO.matcher(t).find();
        boolean confirmYesExcluded = CONFIRM_YES_EXCLUDE.matcher(t).find();
        boolean hasConfirmYes = !confirmYesExcluded && CONFIRM_YES.matcher(t).find();
        boolean hasConfirmNo = CONFIRM_NO.matcher(t).find();
        boolean hasRequestSlots = REQUEST_SLOTS.matcher(t).find();
        boolean hasChangeDoctor = CHANGE_DOCTOR.matcher(t).find();
        boolean hasProvideDetails = PROVIDE_DETAILS.matcher(t).find();

        if (hasAskDoctorInfo && !hasRequestSlots) {
            intents.add(ConversationIntent.ASK_DOCTOR_INFO);
        }
        if (hasConfirmYes && !hasAskDoctorInfo) {
            intents.add(ConversationIntent.CONFIRM_YES);
        }
        if (hasConfirmYes && hasAskDoctorInfo) {
            intents.add(ConversationIntent.CONFIRM_YES);
            intents.add(ConversationIntent.ASK_DOCTOR_INFO);
        }
        if (hasConfirmNo) {
            intents.add(ConversationIntent.CONFIRM_NO);
        }
        if (hasRequestSlots && !hasAskDoctorInfo) {
            intents.add(ConversationIntent.REQUEST_SLOTS);
        }
        if (hasChangeDoctor) {
            intents.add(ConversationIntent.CHANGE_DOCTOR);
        }
        if (hasProvideDetails && awaitingBookingConfirmation) {
            intents.add(ConversationIntent.PROVIDE_DETAILS);
        }

        if (CHECK_APPOINTMENTS.matcher(t).find()) {
            intents.clear();
            intents.add(ConversationIntent.CHECK_APPOINTMENTS);
            return new IntentResult(intents, false);
        }
        if (CANCEL.matcher(t).find() && !RESCHEDULE.matcher(t).find()) {
            intents.clear();
            intents.add(ConversationIntent.CANCEL_APPOINTMENT);
            return new IntentResult(intents, false);
        }
        if (RESCHEDULE.matcher(t).find()) {
            intents.clear();
            intents.add(ConversationIntent.RESCHEDULE_APPOINTMENT);
            return new IntentResult(intents, false);
        }
        if (BOOK.matcher(t).find() && !awaitingBookingConfirmation) {
            intents.add(ConversationIntent.BOOK_APPOINTMENT);
        }

        boolean hasConflict = intents.contains(ConversationIntent.CONFIRM_YES)
                && (intents.contains(ConversationIntent.ASK_DOCTOR_INFO) || intents.contains(ConversationIntent.CHANGE_DOCTOR));

        if (intents.isEmpty()) {
            intents.add(ConversationIntent.NONE);
        }

        return new IntentResult(intents, hasConflict);
    }

    public ConversationIntent classify(String userText, boolean awaitingBookingConfirmation) {
        IntentResult r = classifyMulti(userText, awaitingBookingConfirmation);
        if (r.getIntents().isEmpty() || r.getIntents().contains(ConversationIntent.NONE)) {
            return ConversationIntent.NONE;
        }
        if (r.hasConflict() && r.hasIntent(ConversationIntent.ASK_DOCTOR_INFO)) {
            return ConversationIntent.ASK_DOCTOR_INFO;
        }
        if (r.hasConflict() && r.hasIntent(ConversationIntent.CHANGE_DOCTOR)) {
            return ConversationIntent.CHANGE_DOCTOR;
        }
        return r.getIntents().iterator().next();
    }

    public boolean isAskDoctorInfo(String userText) {
        return classifyMulti(userText, false).hasIntent(ConversationIntent.ASK_DOCTOR_INFO);
    }

    public boolean isInterruptRequestingDoctorInfo(String userText, boolean awaitingConfirm) {
        return awaitingConfirm && ASK_DOCTOR_INFO.matcher(userText != null ? userText : "").find();
    }
}
