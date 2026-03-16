package com.ai.receptionist.service;

import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.entity.Tenant;
import com.ai.receptionist.repository.TenantRepository;
import com.ai.receptionist.utils.LogSanitizer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scheduled service that sends appointment reminder calls on the day of appointment.
 * Respects business hours from tenant_config. Same-day reminders only.
 */
@Service
@RequiredArgsConstructor
public class ReminderSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(ReminderSchedulerService.class);

    private final AppointmentService appointmentService;
    private final OutboundCallService outboundCallService;
    private final TenantService tenantService;
    private final TenantRepository tenantRepository;

    @Scheduled(fixedDelayString = "${reminder.check-interval-ms:300000}") // Every 5 minutes
    public void checkAndSendReminders() {
        try {
            LocalDate today = LocalDate.now();
            List<Tenant> activeTenants = tenantRepository.findByActiveTrue();
            
            for (Tenant tenant : activeTenants) {
                if (!isWithinBusinessHours(tenant.getId())) {
                    log.debug("Tenant {} outside business hours, skipping reminders", tenant.getId());
                    continue;
                }
                processRemindersForTenant(tenant.getId(), today);
            }
        } catch (Exception e) {
            log.error("Reminder scheduler error", e);
        }
    }

    private void processRemindersForTenant(Long tenantId, LocalDate today) {
        List<Appointment> todayAppointments = appointmentService.getUnremindedAppointmentsForDate(today);

        if (todayAppointments.isEmpty()) {
            log.debug("No unreminded appointments for tenant {} on {}", tenantId, today);
            return;
        }

        log.info("Found {} unreminded appointments for tenant {} on {}", todayAppointments.size(), tenantId, today);

        for (Appointment appt : todayAppointments) {
            try {
                if (!appt.getStatus().equals(Appointment.Status.CONFIRMED)) {
                    log.debug("Skipping appointment {} - not confirmed", appt.getId());
                    appointmentService.markReminded(appt.getId());
                    continue;
                }

                if (appt.getSlot() == null || appt.getSlot().getSlotDate() == null) {
                    log.warn("Invalid slot for appointment {}", appt.getId());
                    appointmentService.markReminded(appt.getId());
                    continue;
                }

                String patientPhone = appt.getPatient().getTwilioPhone();
                if (patientPhone == null || patientPhone.isBlank()) {
                    patientPhone = appt.getPatient().getPhone();
                }
                if (patientPhone == null || patientPhone.isBlank()) {
                    log.warn("No phone for patient in appointment {}", appt.getId());
                    appointmentService.markReminded(appt.getId());
                    continue;
                }

                String callSid = outboundCallService.initiateCall(
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

                if (callSid != null && !callSid.isBlank()) {
                    appointmentService.markReminded(appt.getId());
                    log.info("Reminder call initiated | appointmentId={} patient={} doctor={} phone={}",
                            appt.getId(), LogSanitizer.maskName(appt.getPatient().getName()),
                            appt.getDoctor().getName(), LogSanitizer.maskPhone(patientPhone));
                }
            } catch (Exception e) {
                log.error("Failed to send reminder for appointment {}", appt.getId(), e);
            }
        }
    }

    private boolean isWithinBusinessHours(Long tenantId) {
        try {
            String hoursConfig = tenantService.getConfig(tenantId, "business_hours", "9:00 AM - 5:00 PM");
            BusinessHours hours = parseBusinessHours(hoursConfig);
            LocalTime now = LocalTime.now();
            return !now.isBefore(hours.start) && now.isBefore(hours.end);
        } catch (Exception e) {
            log.warn("Error parsing business hours for tenant {}", tenantId, e);
            LocalTime now = LocalTime.now();
            return !now.isBefore(LocalTime.of(9, 0)) && now.isBefore(LocalTime.of(17, 0));
        }
    }

    private BusinessHours parseBusinessHours(String config) {
        if (config == null || config.isBlank()) {
            return new BusinessHours(LocalTime.of(9, 0), LocalTime.of(17, 0));
        }

        Pattern p = Pattern.compile(
            "([0-9]{1,2}):([0-9]{2})\\s*(AM|PM)?\\s*-\\s*([0-9]{1,2}):([0-9]{2})\\s*(AM|PM)?",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(config);

        if (!m.find()) {
            return new BusinessHours(LocalTime.of(9, 0), LocalTime.of(17, 0));
        }

        int startHr = Integer.parseInt(m.group(1));
        int startMin = Integer.parseInt(m.group(2));
        String startAmPm = m.group(3);
        int endHr = Integer.parseInt(m.group(4));
        int endMin = Integer.parseInt(m.group(5));
        String endAmPm = m.group(6);

        if (startAmPm != null) startHr = convert12to24(startHr, startAmPm);
        if (endAmPm != null) endHr = convert12to24(endHr, endAmPm);

        return new BusinessHours(LocalTime.of(startHr, startMin), LocalTime.of(endHr, endMin));
    }

    private int convert12to24(int hr, String amPm) {
        if (amPm.equalsIgnoreCase("PM")) return hr == 12 ? 12 : hr + 12;
        else return hr == 12 ? 0 : hr;
    }

    private static class BusinessHours {
        LocalTime start, end;
        BusinessHours(LocalTime start, LocalTime end) { this.start = start; this.end = end; }
    }
}
