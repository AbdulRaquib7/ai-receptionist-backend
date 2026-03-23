package com.ai.receptionist.service;

import com.ai.receptionist.component.CallerPhoneResolver;
import com.ai.receptionist.dto.PendingActionDto;
import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.utils.LogSanitizer;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Executes confirmed actions (book, cancel, reschedule) against the database.
 * Called only when user has confirmed; uses existing AppointmentService so
 * schema and business logic remain unchanged.
 */
@Service
@RequiredArgsConstructor
public class ConfirmationExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationExecutionService.class);

    private final AppointmentService appointmentService;
    private final CallerPhoneResolver callerPhoneResolver;
    private final PhoneNumberNormalizationService phoneNumberNormalizationService;

    /**
     * Executes the pending action (BOOK, CANCEL, RESCHEDULE) and returns a
     * short human-friendly message. Clears pending on success.
     */
    public Optional<String> execute(String callSid, String fromNumber, Long tenantId, PendingActionDto pending) {
        if (pending == null || !pending.isAwaitingConfirmation()) {
            return Optional.empty();
        }

        String callerPhone = callerPhoneResolver.resolve(fromNumber);

        log.info("Executing pending action for call {}: intent={} callerPhone={} doctorKey={} date={} time={} targetPatient={} tenant={}",
                callSid,
                pending.getIntent(),
                LogSanitizer.maskPhone(callerPhone),
                pending.getDoctorKey(),
                pending.getDate(),
                pending.getTime(),
                LogSanitizer.maskName(pending.getTargetPatientName()),
                tenantId);

        switch (pending.getIntent()) {
            case BOOK:
                return executeBook(tenantId, callerPhone, pending);
            case CANCEL:
                return executeCancel(tenantId, callerPhone, pending);
            case RESCHEDULE:
                return executeReschedule(tenantId, callerPhone, pending);
            default:
                return Optional.empty();
        }
    }

    private Optional<String> executeBook(Long tenantId, String callerPhone, PendingActionDto p) {
        if (StringUtils.isBlank(p.getDoctorKey()) || StringUtils.isBlank(p.getDate()) || StringUtils.isBlank(p.getTime())) {
            log.warn("Book action missing required fields");
            return Optional.of("I don't have the full booking details. Let's try again.");
        }

        String rawPatientPhone = p.getPatientPhone();
        String normalizedPatientPhone = rawPatientPhone;
        if (StringUtils.isNotBlank(rawPatientPhone) && !rawPatientPhone.trim().startsWith("+")) {
            // If user provided a number without country code, try inferring it from the caller.
            normalizedPatientPhone = phoneNumberNormalizationService
                    .normalizeToE164(rawPatientPhone, callerPhone)
                    .orElse(null);
            if (normalizedPatientPhone == null) {
                return Optional.of("Please tell me your full phone number including country code (example: +1..., +44...). Once I have that, I can book the appointment.");
            }
        }

        Optional<Appointment> result = appointmentService.bookAppointment(
                tenantId,
                callerPhone,
                p.getPatientName(),
                normalizedPatientPhone,
                p.getDoctorKey(),
                p.getDate(),
                p.getTime()
        );
        if (result.isPresent()) {
            log.info("Booked appointment for {} {}", LogSanitizer.maskPhone(callerPhone), LogSanitizer.maskName(p.getPatientName()));
            return Optional.of("You're all set! Your appointment is confirmed for " + p.getDate() + " at " + p.getTime() + ". We'll see you then. Take care!");
        }
        log.warn("Book action failed at persistence: doctorKey={} date={} time={}", p.getDoctorKey(), p.getDate(), p.getTime());
        return Optional.of("That slot's no longer available. Want to try a different time?");
    }

    private Optional<String> executeCancel(Long tenantId, String callerPhone, PendingActionDto p) {
        boolean ok = appointmentService.cancelAppointment(tenantId, callerPhone, p.getTargetPatientName());
        if (ok) {
            log.info("Cancelled appointment for {} ({})", LogSanitizer.maskPhone(callerPhone), LogSanitizer.maskName(p.getTargetPatientName()));
            return Optional.of("Done, it's cancelled. Anything else I can help with?");
        }
        return Optional.of("I couldn't find that appointment. Want to try again or book a new one?");
    }

    private Optional<String> executeReschedule(Long tenantId, String callerPhone, PendingActionDto p) {
        if (StringUtils.isBlank(p.getNewDate()) || StringUtils.isBlank(p.getNewTime())) {
            return Optional.of("I need the new date and time. What would work for you?");
        }
        Optional<Appointment> result = appointmentService.rescheduleAppointment(
                tenantId,
                callerPhone,
                p.getTargetPatientName(),
                p.getDoctorKey(),
                p.getNewDate(),
                p.getNewTime()
        );
        if (result.isPresent()) {
            log.info("Rescheduled for {} to {} {}", LogSanitizer.maskPhone(callerPhone), p.getNewDate(), p.getNewTime());
            return Optional.of("All set! Your appointment is moved to " + p.getNewDate() + " at " + p.getNewTime() + ". Anything else?");
        }
        return Optional.of("That new slot isn't available. Want to pick another time?");
    }
}
