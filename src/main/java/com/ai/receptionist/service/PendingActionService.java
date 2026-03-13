package com.ai.receptionist.service;

import com.ai.receptionist.dto.PendingActionDto;
import com.ai.receptionist.utils.LogSanitizer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Per-call in-memory store for pending actions (book/cancel/reschedule).
 * LLM sets a pending action when it asks for confirmation; backend executes
 * only when user confirms. State is not persisted to DB until execution.
 *
 * Uses a Caffeine cache with TTL (30 min) and size limit (500) to prevent
 * unbounded memory growth from abandoned calls.
 */
@Service
public class PendingActionService {

    private static final Logger log = LoggerFactory.getLogger(PendingActionService.class);

    /** TTL-bounded cache: max 500 pending actions, auto-expire after 30 minutes */
    private final Cache<String, PendingActionDto> pendingByCall = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

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
                    LogSanitizer.maskName(action.getPatientName()),
                    LogSanitizer.maskName(action.getTargetPatientName()));
        }
    }

    public PendingActionDto getPending(String callSid) {
        return pendingByCall.getIfPresent(callSid);
    }

    /**
     * Atomic check-and-get: returns the pending action only if it exists and is awaiting
     * confirmation. Eliminates the TOCTOU race between hasPendingConfirmation() and getPending().
     */
    public Optional<PendingActionDto> getIfAwaitingConfirmation(String callSid) {
        PendingActionDto p = pendingByCall.getIfPresent(callSid);
        if (p != null && p.isAwaitingConfirmation()) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    public boolean hasPendingConfirmation(String callSid) {
        PendingActionDto p = pendingByCall.getIfPresent(callSid);
        return p != null && p.isAwaitingConfirmation();
    }

    public void clearPending(String callSid) {
        pendingByCall.invalidate(callSid);
    }
}
