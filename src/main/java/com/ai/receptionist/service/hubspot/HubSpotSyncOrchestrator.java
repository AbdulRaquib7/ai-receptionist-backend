package com.ai.receptionist.service.hubspot;

import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.entity.Patient;
import com.ai.receptionist.repository.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class HubSpotSyncOrchestrator {

    private final HubSpotContactSyncService contactSyncService;
    private final HubSpotAppointmentSyncService appointmentSyncService;
    private final HubSpotDoctorSyncService doctorSyncService;
    private final TenantRepository tenantRepository;

    public HubSpotSyncOrchestrator(
            HubSpotContactSyncService contactSyncService,
            HubSpotAppointmentSyncService appointmentSyncService,
            HubSpotDoctorSyncService doctorSyncService,
            TenantRepository tenantRepository) {
        this.contactSyncService = contactSyncService;
        this.appointmentSyncService = appointmentSyncService;
        this.doctorSyncService = doctorSyncService;
        this.tenantRepository = tenantRepository;
    }

    /**
     * Sync everything for a new appointment booking
     * This is called when an appointment is confirmed
     */
    public void syncNewAppointment(Appointment appointment) {
        try {
            log.info("Starting full sync for new appointment {}", appointment.getId());

            // 1. Sync doctor first (if not already synced)
            Doctor doctor = appointment.getDoctor();
            if (doctor != null) {
                doctorSyncService.syncDoctorToHubSpot(doctor);
            }

            // 2. Sync patient contact
            Patient patient = appointment.getPatient();
            if (patient != null) {
                contactSyncService.syncPatientToHubSpot(patient);
            }

            // 3. Sync appointment itself
            appointmentSyncService.syncAppointmentToHubSpot(appointment);

            log.info("Completed full sync for appointment {}", appointment.getId());
        } catch (Exception e) {
            log.error("Error in appointment sync orchestration", e);
            // Don't throw exception to avoid breaking the appointment flow
        }
    }

    /**
     * Sync appointment status changes (e.g., cancelled, confirmed, attended)
     */
    public void syncAppointmentStatusChange(Appointment appointment, String hubspotAppointmentId) {
        try {
            log.info("Syncing appointment status change for appointment {}", appointment.getId());
            appointmentSyncService.updateAppointmentInHubSpot(appointment, hubspotAppointmentId);
        } catch (Exception e) {
            log.error("Error syncing appointment status change", e);
        }
    }

    /**
     * Full sync for a tenant - syncs all doctors, patients, and appointments
     * This is typically run once during initial setup or periodically
     */
    public void fullTenantSync(Long tenantId) {
        try {
            log.info("Starting full tenant sync for tenant {}", tenantId);

            // Verify tenant exists
            if (!tenantRepository.existsById(tenantId)) {
                log.warn("Tenant {} does not exist", tenantId);
                return;
            }

            // 1. Sync all doctors for the tenant
            log.info("Syncing all doctors for tenant {}", tenantId);
            doctorSyncService.syncAllDoctorsForTenant(tenantId);

            // 2. Sync all appointments for the tenant
            log.info("Syncing all appointments for tenant {}", tenantId);
            appointmentSyncService.syncAllAppointmentsForTenant(tenantId);

            log.info("Completed full tenant sync for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("Error in full tenant sync", e);
        }
    }

    /**
     * Sync a single patient
     */
    public void syncPatient(Patient patient) {
        try {
            log.info("Syncing patient {} to HubSpot", patient.getId());
            contactSyncService.syncPatientToHubSpot(patient);
        } catch (Exception e) {
            log.error("Error syncing patient", e);
        }
    }

    /**
     * Sync a single doctor
     */
    public void syncDoctor(Doctor doctor) {
        try {
            log.info("Syncing doctor {} to HubSpot", doctor.getId());
            doctorSyncService.syncDoctorToHubSpot(doctor);
        } catch (Exception e) {
            log.error("Error syncing doctor", e);
        }
    }
}
