package com.ai.receptionist.conversation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-call conversation state. Maintains current flow, previous state for
 * interruption recovery, and collected data. All business data from DB.
 */
public class ConversationStateContext {

    private ConversationState currentState = ConversationState.START;
    private ConversationState previousState;
    private final Map<String, Object> collectedData = new LinkedHashMap<>();
    private boolean awaitingConfirmation;

    public ConversationState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(ConversationState currentState) {
        this.previousState = this.currentState;
        this.currentState = currentState;
    }

    public ConversationState getPreviousState() {
        return previousState;
    }

    public void setPreviousState(ConversationState previousState) {
        this.previousState = previousState;
    }

    public Map<String, Object> getCollectedData() {
        return collectedData;
    }

    public void put(String key, Object value) {
        collectedData.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = collectedData.get(key);
        return v != null && type.isInstance(v) ? (T) v : null;
    }

    public String getString(String key) {
        return get(key, String.class);
    }

    public boolean isAwaitingConfirmation() {
        return awaitingConfirmation;
    }

    public void setAwaitingConfirmation(boolean awaitingConfirmation) {
        this.awaitingConfirmation = awaitingConfirmation;
    }

    public void clear() {
        currentState = ConversationState.START;
        previousState = null;
        collectedData.clear();
        awaitingConfirmation = false;
    }

    public boolean isInFlow() {
        return currentState != ConversationState.START
                && currentState != ConversationState.END
                && currentState != ConversationState.GENERAL_QUESTION;
    }
}
