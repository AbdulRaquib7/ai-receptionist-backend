package com.ai.receptionist.service;

import com.ai.receptionist.entity.Appointment;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Scheduled service that sends appointment reminder calls for next-day appointments.
 */
@Service
@RequiredArgsConstructor
public class ReminderSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(ReminderSchedulerService.class);

    private final AppointmentService appointmentService;
    private final OutboundCallService outboundCallService;

    @Scheduled(fixedDelayString = "${reminder.check-interval-ms:600000}") // Every 10 minutes
    public void checkAndSendReminders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        List<Appointment> upcoming = appointmentService.getUnremindedAppointmentsForDate(tomorrow);

        if (upcoming.isEmpty()) {
            log.debug("No unreminded appointments for {}", tomorrow);
            return;
        }

        log.info("Found {} unreminded appointments for {}", upcoming.size(), tomorrow);

        for (Appointment appt : upcoming) {
            try {
                String patientPhone = appt.getPatient().getTwilioPhone();
                if (patientPhone == null || patientPhone.isBlank()) {
                    patientPhone = appt.getPatient().getPhone();
                }
                if (patientPhone == null || patientPhone.isBlank()) {
                    log.warn("No phone number for patient in appointment {}", appt.getId());
                    continue;
                }

                outboundCallService.initiateCall(
                        appt.getTenantId(),
                        patientPhone,
                        Map.of(
                                "appointmentId", appt.getId().toString(),
                                "doctorName", appt.getDoctor().getName(),
                                "date", appt.getSlot().getSlotDate().toString(),
                                "time", appt.getSlot().getStartTime(),
                                "patientName", appt.getPatient().getName()
                        )
                );

                appointmentService.markReminded(appt.getId());
                log.info("Reminder call initiated for appointment {} (patient: {})",
                        appt.getId(), appt.getPatient().getName());

            } catch (Exception e) {
                log.error("Failed to send reminder for appointment {}", appt.getId(), e);
            }
        }
    }
}
