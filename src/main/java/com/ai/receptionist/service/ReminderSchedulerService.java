package com.ai.receptionist.service;

import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.entity.Tenant;
import com.ai.receptionist.repository.AppointmentRepository;
import com.ai.receptionist.repository.TenantRepository;
import com.ai.receptionist.utils.LogSanitizer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Scheduled service that sends appointment reminder calls exactly 1 hour before each appointment.
 * Runs every minute, finds CONFIRMED appointments with reminded=false whose appointment time
 * falls in the next 1-hour window. Respects business hours.
 * Reminded is set immediately after successfully initiating the outbound call to avoid duplicates.
 */
@Service
@RequiredArgsConstructor
public class ReminderSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(ReminderSchedulerService.class);

    private final AppointmentRepository appointmentRepository;
    private final OutboundCallService outboundCallService;
    private final TenantService tenantService;
    private final TenantRepository tenantRepository;

    /**
     * Runs every minute. Fetches appointments due for reminder (next 1 hour window),
     * initiates outbound calls. Marked reminded immediately after call initiation.
     */
    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void checkAndSendReminders() {
        try {
            List<Tenant> activeTenants = tenantRepository.findByActiveTrue();
            for (Tenant tenant : activeTenants) {
                if (!isWithinBusinessHours(tenant.getId())) {
                    log.debug("Tenant {} outside business hours, skipping reminders", tenant.getId());
                    continue;
                }
                processReminders();
            }
        } catch (Exception e) {
            log.error("Reminder scheduler error", e);
        }
    }

    private void processReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextHour = now.plusHours(1);
        log.info("Checking reminders between {} and {}", now, nextHour);

        List<Appointment> appointments = appointmentRepository.findAppointmentsToRemind(now, nextHour);

        if (appointments.isEmpty()) {
            log.debug("No appointments to remind in window {} - {}", now, nextHour);
            return;
        }

        log.info("Found {} appointment(s) to remind in window {} - {}", appointments.size(), now, nextHour);

        for (Appointment appt : appointments) {
            try {
                sendReminder(appt);
            } catch (Exception e) {
                log.error("Failed to initiate reminder for appointment {}: {}", appt.getId(), e.getMessage(), e);
            }
        }
    }

    private void sendReminder(Appointment appt) {
        if (appt.getStatus() != Appointment.Status.CONFIRMED) return;
        if (appt.isReminded()) return;

        // Dial the patient's actual phone number.
        String dialPhone = appt.getPatient() != null ? appt.getPatient().getPhone() : null;
        if (dialPhone == null || dialPhone.isBlank()) {
            // Fallback: if phone is missing, dial twilioPhone.
            dialPhone = appt.getPatient() != null ? appt.getPatient().getTwilioPhone() : null;
        }
        if (dialPhone == null || dialPhone.isBlank()) {
            log.warn("No phone for patient in appointment {}, skipping", appt.getId());
            return;
        }

        // For DB matching during cancel/reschedule tools, we must keep using twilioPhone.
        String lookupPhone = appt.getPatient() != null ? appt.getPatient().getTwilioPhone() : null;
        if (lookupPhone == null || lookupPhone.isBlank()) {
            // Fallback: if twilioPhone is missing, use the dial number.
            lookupPhone = dialPhone;
        }

        Map<String, String> context = Map.of(
                "appointmentId", appt.getId().toString(),
                "patientName", appt.getPatient() != null ? appt.getPatient().getName() : "Patient",
                "doctorName", appt.getDoctor() != null ? appt.getDoctor().getName() : "Doctor",
                "date", appt.getSlot() != null ? appt.getSlot().getSlotDate().toString() : "",
                "time", appt.getSlot() != null ? appt.getSlot().getStartTime() : "",
                "patientLookupPhone", lookupPhone
        );

        String callSid = outboundCallService.initiateCall(appt.getTenantId(), dialPhone, context);

        if (callSid != null && !callSid.isBlank()) {
            // Mark immediately to avoid duplicate reminder calls while the user is still on the phone.
            appt.setReminded(true);
            appointmentRepository.save(appt);

            log.info("Reminder call initiated | appointmentId={} patient={} doctor={} phone={}",
                    appt.getId(), LogSanitizer.maskName(appt.getPatient().getName()),
                    appt.getDoctor().getName(), LogSanitizer.maskPhone(dialPhone));
        }
    }

    private boolean isWithinBusinessHours(Long tenantId) {
        try {
            String hoursConfig = tenantService.getConfig(tenantId, "business_hours", "9:00 AM - 5:00 PM");
            BusinessHours hours = parseBusinessHours(hoursConfig);
            java.time.LocalTime now = java.time.LocalTime.now();
            return !now.isBefore(hours.start) && now.isBefore(hours.end);
        } catch (Exception e) {
            log.warn("Error parsing business hours for tenant {}", tenantId, e);
            java.time.LocalTime now = java.time.LocalTime.now();
            return !now.isBefore(java.time.LocalTime.of(9, 0)) && now.isBefore(java.time.LocalTime.of(17, 0));
        }
    }

    private BusinessHours parseBusinessHours(String config) {
        if (config == null || config.isBlank()) {
            return new BusinessHours(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0));
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "([0-9]{1,2}):([0-9]{2})\\s*(AM|PM)?\\s*-\\s*([0-9]{1,2}):([0-9]{2})\\s*(AM|PM)?",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(config);
        if (!m.find()) {
            return new BusinessHours(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0));
        }
        int startHr = Integer.parseInt(m.group(1));
        int startMin = Integer.parseInt(m.group(2));
        String startAmPm = m.group(3);
        int endHr = Integer.parseInt(m.group(4));
        int endMin = Integer.parseInt(m.group(5));
        String endAmPm = m.group(6);
        if (startAmPm != null) startHr = convert12to24(startHr, startAmPm);
        if (endAmPm != null) endHr = convert12to24(endHr, endAmPm);
        return new BusinessHours(java.time.LocalTime.of(startHr, startMin), java.time.LocalTime.of(endHr, endMin));
    }

    private int convert12to24(int hr, String amPm) {
        return amPm.equalsIgnoreCase("PM") ? (hr == 12 ? 12 : hr + 12) : (hr == 12 ? 0 : hr);
    }

    private static class BusinessHours {
        final java.time.LocalTime start;
        final java.time.LocalTime end;
        BusinessHours(java.time.LocalTime start, java.time.LocalTime end) {
            this.start = start;
            this.end = end;
        }
    }
}
