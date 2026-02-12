package com.ai.receptionist.service;

import com.ai.receptionist.conversation.ConversationIntent;
import com.ai.receptionist.conversation.IntentResult;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves which intent to act on first when multiple intents exist.
 * Higher priority intents override lower ones (e.g. ASK_DOCTOR_INFO overrides CONFIRM_YES).
 */
@Service
public class IntentPriorityResolver {

    /**
     * Priority order (1 = highest). Lower number = higher priority.
     * When multiple intents exist, the highest-priority one is resolved first.
     */
    private static final int PRIORITY_ASK_DOCTOR_INFO = 1;
    private static final int PRIORITY_CHANGE_DOCTOR = 2;
    private static final int PRIORITY_REQUEST_SLOTS = 3;
    private static final int PRIORITY_PROVIDE_DETAILS = 4;
    private static final int PRIORITY_CONFIRM_YES = 5;
    private static final int PRIORITY_CONFIRM_NO = 6;
    private static final int PRIORITY_DEFAULT = 99;

    /**
     * Returns the single intent to act on when awaiting booking confirmation.
     * If ASK_DOCTOR_INFO or CHANGE_DOCTOR is present, they override CONFIRM_YES.
     */
    public Optional<ConversationIntent> resolveForBookingConfirmation(IntentResult result) {
        if (result == null || result.getIntents().isEmpty()) return Optional.empty();
        Set<ConversationIntent> intents = result.getIntents();

        // Strict gate: multiple intents = do NOT book; resolve higher priority first
        if (intents.size() > 1 && result.hasConflict()) {
            return Optional.of(getHighestPriority(intents));
        }

        if (intents.size() == 1) {
            ConversationIntent single = intents.iterator().next();
            return Optional.of(single);
        }

        return Optional.of(getHighestPriority(intents));
    }

    /**
     * Returns true if booking is allowed: exactly CONFIRM_YES and no conflict.
     */
    public boolean isBookingAllowed(IntentResult result) {
        if (result == null) return false;
        return result.isSingleIntent(ConversationIntent.CONFIRM_YES) && !result.hasConflict();
    }

    /**
     * Returns true if we should defer confirmation and handle doctor-info first.
     */
    public boolean shouldDeferConfirmationForDoctorInfo(IntentResult result) {
        if (result == null || result.getIntents().isEmpty()) return false;
        return result.hasIntent(ConversationIntent.ASK_DOCTOR_INFO)
                || result.hasIntent(ConversationIntent.CHANGE_DOCTOR);
    }

    private static int priority(ConversationIntent intent) {
        if (intent == null) return PRIORITY_DEFAULT;
        switch (intent) {
            case ASK_DOCTOR_INFO: return PRIORITY_ASK_DOCTOR_INFO;
            case CHANGE_DOCTOR: return PRIORITY_CHANGE_DOCTOR;
            case REQUEST_SLOTS: return PRIORITY_REQUEST_SLOTS;
            case PROVIDE_DETAILS: return PRIORITY_PROVIDE_DETAILS;
            case CONFIRM_YES: return PRIORITY_CONFIRM_YES;
            case CONFIRM_NO: return PRIORITY_CONFIRM_NO;
            default: return PRIORITY_DEFAULT;
        }
    }

    private static ConversationIntent getHighestPriority(Set<ConversationIntent> intents) {
        return intents.stream()
                .min(Comparator.comparingInt(IntentPriorityResolver::priority))
                .orElse(ConversationIntent.NONE);
    }
}
