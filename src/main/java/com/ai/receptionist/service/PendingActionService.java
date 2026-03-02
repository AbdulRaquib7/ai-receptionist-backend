package com.ai.receptionist.service;

import com.ai.receptionist.dto.PendingActionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PendingActionService.class);

    private final Map<String, PendingActionDto> pendingByCall = new ConcurrentHashMap<>();

    public void setPending(String callSid, PendingActionDto action) {
        if (action != null) {
            action.setAwaitingConfirmation(true);
            pendingByCall.put(callSid, action);
            log.info("Pending action set for call {}: intent={} doctorKey={} date={} time={} patientName={} targetPatient={}",
                    callSid,
                    action.getIntent(),
                    action.getDoctorKey(),
                    action.getDate(),
                    action.getTime(),
                    action.getPatientName(),
                    action.getTargetPatientName());
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
