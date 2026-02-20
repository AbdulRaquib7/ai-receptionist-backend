package com.ai.receptionist.utils;

/**
 * Disambiguated intents for the conversation flow.
 * Prevents slot fetch on doctor-info requests and booking on interrupt.
 */
public enum ConversationIntent {
    ASK_DOCTOR_INFO,
    REQUEST_SLOTS,
    CONFIRM_YES,
    CONFIRM_NO,
    CHANGE_DOCTOR,
    PROVIDE_DETAILS,
    BOOK_APPOINTMENT,
    CANCEL_APPOINTMENT,
    RESCHEDULE_APPOINTMENT,
    CHECK_APPOINTMENTS,
    GENERAL_QUERY,
    NONE
}
