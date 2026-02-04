package com.ai.receptionist.entity;

/**
 * State machine for doctor appointment booking conversation.
 */
public enum ConversationState {
    GREETING,
    INTENT_CONFIRMATION,
    DOCTOR_LIST,
    DOCTOR_SELECTED,
    SLOT_SELECTION,
    USER_DETAILS,
    CONFIRMATION,
    COMPLETED,
    CANCEL_CONFIRMATION,
    RESCHEDULE_SLOT_SELECTION,
    RESCHEDULE_CONFIRMATION
}
