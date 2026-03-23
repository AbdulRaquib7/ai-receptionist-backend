package com.ai.receptionist.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Tracks outbound reminder calls so we can mark appointments as reminded when the call ends.
 * CallSid -> appointmentId. Cleared when call ends or after TTL.
 */
@Service
public class ReminderCallContextService {

    private final Cache<String, Long> callSidToAppointmentId = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(2, TimeUnit.HOURS)
            .build();

    public void setReminderContext(String callSid, Long appointmentId) {
        if (callSid != null && appointmentId != null) {
            callSidToAppointmentId.put(callSid, appointmentId);
        }
    }

    public Optional<Long> getAppointmentId(String callSid) {
        return Optional.ofNullable(callSidToAppointmentId.getIfPresent(callSid));
    }

    public boolean isReminderCall(String callSid) {
        return getAppointmentId(callSid).isPresent();
    }

    /**
     * Returns the appointment ID if this was a reminder call, and removes it from the cache.
     * Call this when the call ends to mark reminded and avoid duplicate tracking.
     */
    public Optional<Long> getAndClear(String callSid) {
        Long id = callSidToAppointmentId.getIfPresent(callSid);
        if (id != null) {
            callSidToAppointmentId.invalidate(callSid);
            return Optional.of(id);
        }
        return Optional.empty();
    }
}
