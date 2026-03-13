package com.ai.receptionist.service;

import com.ai.receptionist.entity.CallSession;
import com.ai.receptionist.entity.CallSession.Direction;
import com.ai.receptionist.entity.CallSession.Outcome;
import com.ai.receptionist.entity.CallSession.Status;
import com.ai.receptionist.repository.CallSessionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CallSessionService {

    private static final Logger log = LoggerFactory.getLogger(CallSessionService.class);

    private final CallSessionRepository callSessionRepository;

    /**
     * Create a new call session when an inbound call arrives.
     */
    @Transactional
    public CallSession createInbound(Long tenantId, String callSid, String fromNumber, String toNumber) {
        // Idempotent: if a session already exists for this callSid, return it
        Optional<CallSession> existing = callSessionRepository.findByTwilioCallSid(callSid);
        if (existing.isPresent()) {
            return existing.get();
        }

        CallSession session = CallSession.builder()
                .tenantId(tenantId)
                .twilioCallSid(callSid)
                .direction(Direction.INBOUND)
                .fromNumber(fromNumber)
                .toNumber(toNumber)
                .status(Status.RINGING)
                .build();
        session = callSessionRepository.save(session);
        log.info("[{}] Call session created: id={} direction=INBOUND", callSid, session.getId());
        return session;
    }

    /**
     * Create a new outbound call session.
     */
    @Transactional
    public CallSession createOutbound(Long tenantId, String callSid, String fromNumber, String toNumber) {
        CallSession session = CallSession.builder()
                .tenantId(tenantId)
                .twilioCallSid(callSid)
                .direction(Direction.OUTBOUND)
                .fromNumber(fromNumber)
                .toNumber(toNumber)
                .status(Status.RINGING)
                .build();
        session = callSessionRepository.save(session);
        log.info("[{}] Outbound call session created: id={}", callSid, session.getId());
        return session;
    }

    /**
     * Mark a call as in-progress (first utterance received).
     */
    @Transactional
    public void markInProgress(String callSid) {
        callSessionRepository.findByTwilioCallSid(callSid).ifPresent(session -> {
            if (session.getStatus() == Status.RINGING) {
                session.setStatus(Status.IN_PROGRESS);
                callSessionRepository.save(session);
                log.debug("[{}] Call session marked IN_PROGRESS", callSid);
            }
        });
    }

    /**
     * Set the outcome when an action is confirmed (BOOKED, CANCELLED, RESCHEDULED).
     */
    @Transactional
    public void setOutcome(String callSid, Outcome outcome) {
        callSessionRepository.findByTwilioCallSid(callSid).ifPresent(session -> {
            session.setOutcome(outcome);
            callSessionRepository.save(session);
            log.info("[{}] Call outcome set to {}", callSid, outcome);
        });
    }

    /**
     * Complete a call session: set status, end time, and duration.
     */
    @Transactional
    public void completeCall(String callSid) {
        callSessionRepository.findByTwilioCallSid(callSid).ifPresent(session -> {
            Instant now = Instant.now();
            session.setStatus(Status.COMPLETED);
            session.setEndedAt(now);
            if (session.getStartedAt() != null) {
                session.setDurationSeconds((int) Duration.between(session.getStartedAt(), now).getSeconds());
            }
            // If no explicit outcome was set, default to INFO_ONLY
            if (session.getOutcome() == null) {
                session.setOutcome(Outcome.INFO_ONLY);
            }
            callSessionRepository.save(session);
            log.info("[{}] Call session completed: duration={}s outcome={}", callSid,
                    session.getDurationSeconds(), session.getOutcome());
        });
    }

    /**
     * Update call session from a Twilio status callback.
     */
    @Transactional
    public void updateFromTwilioStatus(String callSid, String twilioStatus, String callDuration) {
        callSessionRepository.findByTwilioCallSid(callSid).ifPresent(session -> {
            switch (twilioStatus.toLowerCase()) {
                case "in-progress" -> session.setStatus(Status.IN_PROGRESS);
                case "completed" -> {
                    session.setStatus(Status.COMPLETED);
                    session.setEndedAt(Instant.now());
                    if (callDuration != null) {
                        try {
                            session.setDurationSeconds(Integer.parseInt(callDuration));
                        } catch (NumberFormatException ignored) {}
                    }
                    if (session.getOutcome() == null) {
                        session.setOutcome(Outcome.INFO_ONLY);
                    }
                }
                case "failed", "busy", "no-answer" -> {
                    session.setStatus(Status.FAILED);
                    session.setEndedAt(Instant.now());
                    session.setOutcome(Outcome.FAILED);
                }
                default -> log.debug("[{}] Unhandled Twilio status: {}", callSid, twilioStatus);
            }
            callSessionRepository.save(session);
            log.info("[{}] Call session updated from Twilio status: {}", callSid, twilioStatus);
        });
    }

    /**
     * Store call summary.
     */
    @Transactional
    public void setSummary(String callSid, String summary) {
        callSessionRepository.findByTwilioCallSid(callSid).ifPresent(session -> {
            session.setSummary(summary);
            callSessionRepository.save(session);
            log.info("[{}] Call summary stored ({} chars)", callSid, summary.length());
        });
    }

    public Optional<CallSession> findByCallSid(String callSid) {
        return callSessionRepository.findByTwilioCallSid(callSid);
    }

    /**
     * Tag a call session with the pipeline type (LEGACY or REALTIME_API).
     * Stored in the JSONB metadata field.
     */
    @Transactional
    public void setPipelineTag(String callSid, String pipeline) {
        callSessionRepository.findByTwilioCallSid(callSid).ifPresent(session -> {
            Map<String, Object> meta = session.getMetadata();
            if (meta == null) {
                meta = new java.util.HashMap<>();
            }
            meta.put("pipeline", pipeline);
            session.setMetadata(meta);
            callSessionRepository.save(session);
            log.debug("[{}] Pipeline tagged as {}", callSid, pipeline);
        });
    }

    // --- Metrics queries ---

    public long countSince(LocalDate since, Long tenantId) {
        return callSessionRepository.countSince(since.atStartOfDay().toInstant(ZoneOffset.UTC), tenantId);
    }

    public Map<String, Long> countByOutcome(LocalDate since, Long tenantId) {
        Map<String, Long> result = new LinkedHashMap<>();
        callSessionRepository.countByOutcome(since.atStartOfDay().toInstant(ZoneOffset.UTC), tenantId)
                .forEach(row -> result.put(
                        row[0] != null ? row[0].toString() : "UNKNOWN",
                        (Long) row[1]));
        return result;
    }

    public double avgDuration(LocalDate since, Long tenantId) {
        return callSessionRepository.avgDuration(since.atStartOfDay().toInstant(ZoneOffset.UTC), tenantId);
    }

    public long countFailed(LocalDate since, Long tenantId) {
        return callSessionRepository.countFailed(since.atStartOfDay().toInstant(ZoneOffset.UTC), tenantId);
    }

    public Map<String, Long> countByPipeline(LocalDate since, Long tenantId) {
        Map<String, Long> result = new LinkedHashMap<>();
        callSessionRepository.countByPipeline(since.atStartOfDay().toInstant(ZoneOffset.UTC), tenantId)
                .forEach(row -> result.put(
                        row[0] != null ? row[0].toString() : "UNKNOWN",
                        ((Number) row[1]).longValue()));
        return result;
    }
}
