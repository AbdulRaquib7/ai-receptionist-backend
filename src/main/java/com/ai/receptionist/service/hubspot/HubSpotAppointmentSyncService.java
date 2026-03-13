package com.ai.receptionist.service.hubspot;

import com.ai.receptionist.component.CallerPhoneResolver;
import com.ai.receptionist.dto.hubspot.HubSpotAppointmentDto;
import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.entity.AppointmentSlot;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.entity.Patient;
import com.ai.receptionist.repository.AppointmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class HubSpotAppointmentSyncService {

    @Value("${hubspot.crm.appointment.enabled:true}")
    private boolean appointmentSyncEnabled;

    @Value("${hubspot.crm.sync.batch-size:15}")
    private int batchSize;

    private final HubSpotApiClient hubSpotApiClient;
    private final HubSpotContactSyncService contactSyncService;
    private final AppointmentRepository appointmentRepository;
    private final CallerPhoneResolver callerPhoneResolver;

    public HubSpotAppointmentSyncService(HubSpotApiClient hubSpotApiClient,
                                        HubSpotContactSyncService contactSyncService,
                                        AppointmentRepository appointmentRepository,
                                        CallerPhoneResolver callerPhoneResolver) {
        this.hubSpotApiClient = hubSpotApiClient;
        this.contactSyncService = contactSyncService;
        this.appointmentRepository = appointmentRepository;
        this.callerPhoneResolver = callerPhoneResolver;
    }

    /**
     * Sync a new appointment to HubSpot
     */
    public String syncAppointmentToHubSpot(Appointment appointment) {
        if (!appointmentSyncEnabled) {
            log.debug("Appointment sync to HubSpot is disabled");
            return null;
        }

        try {
            Patient patient = appointment.getPatient();
            Doctor doctor = appointment.getDoctor();
            AppointmentSlot slot = appointment.getSlot();

            // Skip if patient has fallback/synthetic phone number (anonymous caller)
            if (callerPhoneResolver.isFallbackNumber(patient.getPhone())) {
                log.debug("Skipping HubSpot appointment sync for anonymous caller with fallback phone: {}", patient.getPhone());
                return null;
            }

            // First sync the patient contact to HubSpot
            String contactId = contactSyncService.syncPatientToHubSpot(patient);

            // Build appointment properties
            Map<String, Object> properties = buildAppointmentProperties(
                    appointment, patient, doctor, slot, contactId
            );

            log.info("Syncing appointment {} to HubSpot", appointment.getId());
            String appointmentId = hubSpotApiClient.createAppointment(properties);

            // Optionally store HubSpot appointment ID in a custom field
            // appointment.setHubspotAppointmentId(appointmentId);
            // appointmentRepository.save(appointment);

            return appointmentId;
        } catch (Exception e) {
            log.error("Failed to sync appointment to HubSpot", e);
            return null;
        }
    }

    /**
     * Update an existing appointment in HubSpot
     */
    public void updateAppointmentInHubSpot(Appointment appointment, String hubspotAppointmentId) {
        if (!appointmentSyncEnabled) {
            log.debug("Appointment sync to HubSpot is disabled");
            return;
        }

        try {
            Patient patient = appointment.getPatient();
            Doctor doctor = appointment.getDoctor();
            AppointmentSlot slot = appointment.getSlot();
            String contactId = extractContactIdFromPatient(patient);

            Map<String, Object> properties = buildAppointmentProperties(
                    appointment, patient, doctor, slot, contactId
            );

            log.info("Updating appointment {} in HubSpot", appointment.getId());
            hubSpotApiClient.updateAppointment(hubspotAppointmentId, properties);
        } catch (Exception e) {
            log.error("Failed to update appointment in HubSpot", e);
        }
    }

    /**
     * Sync all appointments for a tenant to HubSpot (batch operation)
     */
    public void syncAllAppointmentsForTenant(Long tenantId) {
        try {
            List<Appointment> appointments = appointmentRepository.findByTenantId(tenantId);
            log.info("Syncing {} appointments for tenant {} to HubSpot", appointments.size(), tenantId);

            for (int i = 0; i < appointments.size(); i += batchSize) {
                int end = Math.min(i + batchSize, appointments.size());
                List<Appointment> batch = appointments.subList(i, end);

                for (Appointment appointment : batch) {
                    syncAppointmentToHubSpot(appointment);
                }

                // Rate limiting between batches
                if (end < appointments.size()) {
                    Thread.sleep(100);
                }
            }
            log.info("Completed syncing appointments for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("Error syncing appointments batch for tenant {}", tenantId, e);
        }
    }

    /**
     * Build HubSpot appointment custom object properties
     */
    private Map<String, Object> buildAppointmentProperties(
            Appointment appointment,
            Patient patient,
            Doctor doctor,
            AppointmentSlot slot,
            String contactId) {

        Map<String, Object> properties = new HashMap<>();

        // Title
        String title = String.format("%s - %s", patient.getName(), doctor.getName());
        properties.put("title", title);

        // Appointment datetime details
        if (slot.getSlotDate() != null && slot.getStartTime() != null) {
            LocalDateTime startDateTime = parseSlotDateTime(slot.getSlotDate(), slot.getStartTime());
            LocalDateTime endDateTime = startDateTime.plusMinutes(30); // Default 30-minute duration

            properties.put("hs_datetime", startDateTime.toString());
            properties.put("start_time", slot.getStartTime());
            properties.put("end_time", calculateEndTime(slot.getStartTime()));
            properties.put("appointment_date", slot.getSlotDate().toString());
        }

        // Doctor information
        if (doctor != null) {
            properties.put("doctor_name", doctor.getName());
            properties.put("doctor_key", doctor.getKey());
            properties.put("specialization", doctor.getSpecialization());
            properties.put("doctor_id", doctor.getId().toString());
        }

        // Patient information
        if (patient != null) {
            properties.put("patient_name", patient.getName());
            properties.put("patient_phone", patient.getPhone());
            properties.put("patient_id", patient.getId().toString());
        }

        // Status
        properties.put("status", appointment.getStatus().toString());
        properties.put("reminded", appointment.isReminded() ? "true" : "false");

        // Association with contact
        if (contactId != null) {
            properties.put("hs_contact_id", contactId);
            properties.put("contact_id", contactId);
        }

        // External IDs for reference
        properties.put("external_appointment_id", appointment.getId().toString());
        properties.put("tenant_id", appointment.getTenantId().toString());

        // Metadata
        properties.put("created_at", appointment.getCreatedAt().toString());

        return properties;
    }

    /**
     * Parse slot date and time into LocalDateTime
     */
    private LocalDateTime parseSlotDateTime(LocalDate slotDate, String startTime) {
        try {
            // startTime is typically in format "09:00 AM" or "14:30"
            LocalTime time;
            if (startTime.contains("AM") || startTime.contains("PM")) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a");
                time = LocalTime.parse(startTime, formatter);
            } else {
                time = LocalTime.parse(startTime);
            }
            return LocalDateTime.of(slotDate, time);
        } catch (Exception e) {
            log.warn("Could not parse slot time: {}", startTime, e);
            return LocalDateTime.of(slotDate, LocalTime.NOON);
        }
    }

    /**
     * Calculate end time by adding 30 minutes to start time
     */
    private String calculateEndTime(String startTime) {
        try {
            LocalTime time;
            if (startTime.contains("AM") || startTime.contains("PM")) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a");
                time = LocalTime.parse(startTime, formatter);
            } else {
                time = LocalTime.parse(startTime);
            }
            LocalTime endTime = time.plusMinutes(30);
            return endTime.toString();
        } catch (Exception e) {
            log.warn("Could not calculate end time from: {}", startTime);
            return startTime;
        }
    }

    /**
     * Extract or generate HubSpot contact ID from patient
     */
    private String extractContactIdFromPatient(Patient patient) {
        // In production, you would retrieve the stored HubSpot contact ID
        return "contact_" + patient.getId();
    }

    /**
     * Convert Appointment entity to DTO for API responses
     */
    public HubSpotAppointmentDto convertToAppointmentDto(Appointment appointment) {
        Patient patient = appointment.getPatient();
        Doctor doctor = appointment.getDoctor();
        AppointmentSlot slot = appointment.getSlot();

        LocalDateTime startDateTime = parseSlotDateTime(slot.getSlotDate(), slot.getStartTime());
        LocalDateTime endDateTime = startDateTime.plusMinutes(30);

        return HubSpotAppointmentDto.builder()
                .title(String.format("%s - %s", patient.getName(), doctor.getName()))
                .startDateTime(startDateTime)
                .endDateTime(endDateTime)
                .doctorName(doctor.getName())
                .specialization(doctor.getSpecialization())
                .patientName(patient.getName())
                .patientPhone(patient.getPhone())
                .status(appointment.getStatus().toString())
                .externalAppointmentId(appointment.getId())
                .externalDoctorId(doctor.getId())
                .build();
    }
}
