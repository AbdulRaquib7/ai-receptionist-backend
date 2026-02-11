package com.ai.receptionist.conversation;

/**
 * Result of YES/NO intent classification.
 * Maps all user variations (yes, yeah, yup, correct, no, nope, wait, not now) into a boolean or unknown.
 */
public enum YesNoResult {
    YES,
    NO,
    UNKNOWN
}
