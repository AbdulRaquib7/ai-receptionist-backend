package com.ai.receptionist.service;

import com.ai.receptionist.dto.PendingActionDto;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-call in-memory store for pending actions (book/cancel/reschedule).
 * LLM sets a pending action when it asks for confirmation; backend executes
 * only when user confirms. State is not persisted to DB until execution.
 */
@Service
public class PendingActionService {

    private final Map<String, PendingActionDto> pendingByCall = new ConcurrentHashMap<>();

    public void setPending(String callSid, PendingActionDto action) {
        if (action != null) {
            action.setAwaitingConfirmation(true);
            pendingByCall.put(callSid, action);
        }
    }

    public PendingActionDto getPending(String callSid) {
        return pendingByCall.get(callSid);
    }

    public boolean hasPendingConfirmation(String callSid) {
        PendingActionDto p = pendingByCall.get(callSid);
        return p != null && p.isAwaitingConfirmation();
    }

    public void clearPending(String callSid) {
        pendingByCall.remove(callSid);
    }
}
