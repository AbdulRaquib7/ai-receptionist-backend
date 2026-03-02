package com.ai.receptionist.service;

import com.ai.receptionist.dto.PendingActionDto;
import com.ai.receptionist.entity.Appointment;
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

    /**
     * Resolves the caller's phone for DB lookups. For test/anonymous calls,
     * uses patientPhone from pending state if available.
     */
    private String resolveCallerPhone(String fromNumber, PendingActionDto pending) {
        boolean invalid = fromNumber == null || fromNumber.isBlank()
                || fromNumber.startsWith("client:")
                || "anonymous".equalsIgnoreCase(fromNumber);
        if (invalid && pending != null && StringUtils.isNotBlank(pending.getPatientPhone())) {
            return pending.getPatientPhone();
        }
        if (invalid) {
            return "+10000000000"; // fallback for test; may not find appointments
        }
        return fromNumber;
    }

    /**
     * Executes the pending action (BOOK, CANCEL, RESCHEDULE) and returns a
     * short human-friendly message. Clears pending on success.
     */
    public Optional<String> execute(String callSid, String fromNumber, PendingActionDto pending) {
        if (pending == null || !pending.isAwaitingConfirmation()) {
            return Optional.empty();
        }

        String callerPhone = resolveCallerPhone(fromNumber, pending);

        log.info("Executing pending action for call {}: intent={} callerPhone={} doctorKey={} date={} time={} targetPatient={}",
                callSid,
                pending.getIntent(),
                callerPhone,
                pending.getDoctorKey(),
                pending.getDate(),
                pending.getTime(),
                pending.getTargetPatientName());

        switch (pending.getIntent()) {
            case BOOK:
                return executeBook(callerPhone, pending);
            case CANCEL:
                return executeCancel(callerPhone, pending);
            case RESCHEDULE:
                return executeReschedule(callerPhone, pending);
            default:
                return Optional.empty();
        }
    }

    private Optional<String> executeBook(String callerPhone, PendingActionDto p) {
        if (StringUtils.isBlank(p.getDoctorKey()) || StringUtils.isBlank(p.getDate()) || StringUtils.isBlank(p.getTime())) {
            log.warn("Book action missing required fields");
            return Optional.of("I don't have the full booking details. Let's try again.");
        }
        Optional<Appointment> result = appointmentService.bookAppointment(
                callerPhone,
                p.getPatientName(),
                p.getPatientPhone(),
                p.getDoctorKey(),
                p.getDate(),
                p.getTime()
        );
        if (result.isPresent()) {
            log.info("Booked appointment for {} {}", callerPhone, p.getPatientName());
            return Optional.of("You're all set! Your appointment is confirmed for " + p.getDate() + " at " + p.getTime() + ". We'll see you then. Take care!");
        }
        log.warn("Book action failed at persistence: doctorKey={} date={} time={}", p.getDoctorKey(), p.getDate(), p.getTime());
        return Optional.of("That slot's no longer available. Want to try a different time?");
    }

    private Optional<String> executeCancel(String callerPhone, PendingActionDto p) {
        boolean ok = appointmentService.cancelAppointment(callerPhone, p.getTargetPatientName());
        if (ok) {
            log.info("Cancelled appointment for {} ({})", callerPhone, p.getTargetPatientName());
            return Optional.of("Done, it's cancelled. Anything else I can help with?");
        }
        return Optional.of("I couldn't find that appointment. Want to try again or book a new one?");
    }

    private Optional<String> executeReschedule(String callerPhone, PendingActionDto p) {
        if (StringUtils.isBlank(p.getNewDate()) || StringUtils.isBlank(p.getNewTime())) {
            return Optional.of("I need the new date and time. What would work for you?");
        }
        Optional<Appointment> result = appointmentService.rescheduleAppointment(
                callerPhone,
                p.getTargetPatientName(),
                p.getDoctorKey(),
                p.getNewDate(),
                p.getNewTime()
        );
        if (result.isPresent()) {
            log.info("Rescheduled for {} to {} {}", callerPhone, p.getNewDate(), p.getNewTime());
            return Optional.of("All set! Your appointment is moved to " + p.getNewDate() + " at " + p.getNewTime() + ". Anything else?");
        }
        return Optional.of("That new slot isn't available. Want to pick another time?");
    }
}
