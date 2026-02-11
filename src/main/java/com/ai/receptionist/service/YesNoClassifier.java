package com.ai.receptionist.service;

import com.ai.receptionist.conversation.YesNoResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies user utterances into YES, NO, or UNKNOWN.
 * Handles all common variations to ensure confirmations are never missed.
 */
@Service
public class YesNoClassifier {

    private static final Set<String> AFFIRMATIVE_EXACT = Set.of(
            "yes", "yeah", "yep", "ya", "yup", "uh huh", "uh-huh", "ok", "okay",
            "sure", "correct", "right", "absolutely", "definitely", "confirm"
    );

    private static final Set<String> NEGATIVE_EXACT = Set.of(
            "no", "nope", "nah", "cancel", "never mind", "nevermind",
            "don't", "dont", "wait", "not now", "not yet", "hold on"
    );

    private static final Pattern AFFIRMATIVE_PATTERN = Pattern.compile(
            "\\b(yes|yeah|yep|ya|yup|ok|okay|sure|correct|right|confirm|absolutely|definitely)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NEGATIVE_PATTERN = Pattern.compile(
            "\\b(no|nope|nah|cancel|never mind|nevermind|don't|dont|wait|not now|not yet|hold on)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Classify user input. "wait" and "not now" are treated as NO (do not proceed).
     */
    public YesNoResult classify(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return YesNoResult.UNKNOWN;
        }
        String normalized = userInput.trim().toLowerCase();

        if (normalized.length() <= 15) {
            if (AFFIRMATIVE_EXACT.contains(normalized)) {
                return YesNoResult.YES;
            }
            if (NEGATIVE_EXACT.contains(normalized)) {
                return YesNoResult.NO;
            }
        }

        if (AFFIRMATIVE_PATTERN.matcher(normalized).find()) {
            if (NEGATIVE_PATTERN.matcher(normalized).find()) {
                return YesNoResult.UNKNOWN;
            }
            return YesNoResult.YES;
        }
        if (NEGATIVE_PATTERN.matcher(normalized).find()) {
            return YesNoResult.NO;
        }
        if (normalized.length() < 30 && normalized.contains("no")) {
            return YesNoResult.NO;
        }
        return YesNoResult.UNKNOWN;
    }

    public boolean isAffirmative(String userInput) {
        return classify(userInput) == YesNoResult.YES;
    }

    public boolean isNegative(String userInput) {
        return classify(userInput) == YesNoResult.NO;
    }

    public boolean isShortAffirmativeOrNegative(String userInput) {
        if (userInput == null || userInput.length() > 15) return false;
        return classify(userInput) != YesNoResult.UNKNOWN;
    }
}
