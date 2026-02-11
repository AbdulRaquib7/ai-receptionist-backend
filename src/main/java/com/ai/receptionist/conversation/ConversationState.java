package com.ai.receptionist.conversation;

/**
 * State machine for the AI receptionist conversation flow.
 * All states are driven by DB data; no hardcoded business logic.
 */
public enum ConversationState {
    START,
    BOOK_APPOINTMENT,
    RESCHEDULE_APPOINTMENT,
    CANCEL_APPOINTMENT,
    GENERAL_QUESTION,
    CONFIRM_ABORT,
    END
}
