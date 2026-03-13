package com.ai.receptionist.service.hubspot;

import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.repository.DoctorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class HubSpotDoctorSyncService {

    @Value("${hubspot.crm.appointment.enabled:true}")
    private boolean doctorSyncEnabled;

    @Value("${hubspot.crm.sync.batch-size:15}")
    private int batchSize;

    private final HubSpotApiClient hubSpotApiClient;
    private final DoctorRepository doctorRepository;

    public HubSpotDoctorSyncService(HubSpotApiClient hubSpotApiClient, DoctorRepository doctorRepository) {
        this.hubSpotApiClient = hubSpotApiClient;
        this.doctorRepository = doctorRepository;
    }

    /**
     * Sync a doctor/provider to HubSpot as a company or contact
     */
    public String syncDoctorToHubSpot(Doctor doctor) {
        if (!doctorSyncEnabled) {
            log.debug("Doctor sync to HubSpot is disabled");
            return null;
        }

        try {
            Map<String, Object> properties = buildDoctorProperties(doctor);

            log.info("Syncing doctor {} to HubSpot", doctor.getId());
            // For now, we'll treat doctors as special contacts
            String doctorId = hubSpotApiClient.upsertContact(doctor.getKey() + "@doctors.local", properties);

            return doctorId;
        } catch (Exception e) {
            log.error("Failed to sync doctor to HubSpot", e);
            return null;
        }
    }

    /**
     * Sync all doctors for a tenant to HubSpot (batch operation)
     */
    public void syncAllDoctorsForTenant(Long tenantId) {
        try {
            List<Doctor> doctors = doctorRepository.findByTenantIdAndActiveTrue(tenantId);
            log.info("Syncing {} doctors for tenant {} to HubSpot", doctors.size(), tenantId);

            for (int i = 0; i < doctors.size(); i += batchSize) {
                int end = Math.min(i + batchSize, doctors.size());
                List<Doctor> batch = doctors.subList(i, end);

                for (Doctor doctor : batch) {
                    syncDoctorToHubSpot(doctor);
                }

                if (end < doctors.size()) {
                    Thread.sleep(100);
                }
            }
            log.info("Completed syncing doctors for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("Error syncing doctors batch for tenant {}", tenantId, e);
        }
    }

    /**
     * Build HubSpot properties from doctor entity
     */
    private Map<String, Object> buildDoctorProperties(Doctor doctor) {
        Map<String, Object> properties = new HashMap<>();

        // Name
        properties.put("firstname", doctor.getName());
        properties.put("lastname", "Dr.");

        // Professional information
        properties.put("jobtitle", "Doctor");
        properties.put("notes", buildDoctorNotes(doctor));

        // Specialization
        if (doctor.getSpecialization() != null) {
            properties.put("specialization", doctor.getSpecialization());
        }

        // Schedule information
        if (doctor.getScheduleStart() != null) {
            properties.put("schedule_start", doctor.getScheduleStart());
        }
        if (doctor.getScheduleEnd() != null) {
            properties.put("schedule_end", doctor.getScheduleEnd());
        }

        // Status and metadata
        properties.put("lifecyclestage", "subscriber");
        properties.put("hs_lead_status", "OPEN");
        properties.put("is_active", doctor.isActive() ? "true" : "false");
        properties.put("doctor_key", doctor.getKey());
        properties.put("doctor_id", doctor.getId().toString());
        properties.put("tenant_id", doctor.getTenantId().toString());

        return properties;
    }

    /**
     * Build doctor notes combining all relevant information
     */
    private String buildDoctorNotes(Doctor doctor) {
        return String.format("Specialization: %s, Schedule: %s - %s, Status: %s",
                doctor.getSpecialization(),
                doctor.getScheduleStart(),
                doctor.getScheduleEnd(),
                doctor.isActive() ? "Active" : "Inactive");
    }

    /**
     * Sync doctor availability/schedule changes
     */
    public void syncDoctorAvailability(Doctor doctor) {
        if (!doctorSyncEnabled) {
            return;
        }

        try {
            Map<String, Object> properties = new HashMap<>();
            properties.put("schedule_start", doctor.getScheduleStart());
            properties.put("schedule_end", doctor.getScheduleEnd());
            properties.put("is_active", doctor.isActive() ? "true" : "false");

            String doctorContactId = "doctor_" + doctor.getId();
            hubSpotApiClient.updateAppointment(doctorContactId, properties);

            log.info("Updated doctor availability in HubSpot for doctor {}", doctor.getId());
        } catch (Exception e) {
            log.error("Error updating doctor availability in HubSpot", e);
        }
    }
}
