package com.ai.receptionist.service.hubspot;

import com.ai.receptionist.component.CallerPhoneResolver;
import com.ai.receptionist.dto.hubspot.HubSpotContactDto;
import com.ai.receptionist.entity.Patient;
import com.ai.receptionist.repository.PatientRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class HubSpotContactSyncService {

    @Value("${hubspot.crm.contact.enabled:true}")
    private boolean contactSyncEnabled;

    private final HubSpotApiClient hubSpotApiClient;
    private final PatientRepository patientRepository;
    private final CallerPhoneResolver callerPhoneResolver;

    public HubSpotContactSyncService(HubSpotApiClient hubSpotApiClient, PatientRepository patientRepository, CallerPhoneResolver callerPhoneResolver) {
        this.hubSpotApiClient = hubSpotApiClient;
        this.patientRepository = patientRepository;
        this.callerPhoneResolver = callerPhoneResolver;
    }

    /**
     * Sync a patient to HubSpot as a contact
     */
    public String syncPatientToHubSpot(Patient patient) {
        if (!contactSyncEnabled) {
            log.debug("Patient sync to HubSpot is disabled");
            return null;
        }

        // Skip syncing if phone is a fallback/synthetic number (anonymous caller)
        if (callerPhoneResolver.isFallbackNumber(patient.getPhone())) {
            log.debug("Skipping HubSpot sync for patient with fallback phone number: {}", patient.getPhone());
            return null;
        }

        try {
            String email = extractEmailFromPatient(patient);
            Map<String, Object> properties = buildContactProperties(patient);

            log.info("Syncing patient {} to HubSpot", patient.getId());
            String contactId = hubSpotApiClient.upsertContact(email, properties);

            // Update patient with HubSpot contact ID (store in a custom field if available)
            // patient.setHubspotContactId(contactId); // Add this field if needed
            // patientRepository.save(patient);

            return contactId;
        } catch (Exception e) {
            log.error("Failed to sync patient to HubSpot", e);
            return null;
        }
    }

    /**
     * Build HubSpot contact properties from patient entity
     */
    private Map<String, Object> buildContactProperties(Patient patient) {
        Map<String, Object> properties = new HashMap<>();

        // Extract first and last name
        String[] names = patient.getName() != null ? patient.getName().split(" ", 2) : new String[]{"", ""};
        properties.put("firstname", names[0]);
        if (names.length > 1) {
            properties.put("lastname", names[1]);
        }

        // Phone number
        if (patient.getPhone() != null) {
            properties.put("phone", patient.getPhone());
        }

        // Twilio phone for reference
        if (patient.getTwilioPhone() != null) {
            properties.put("mobilephone", patient.getTwilioPhone());
        }

        // Custom properties
        if (patient.getTenantId() != null) {
            properties.put("tenant_id", patient.getTenantId().toString());
        }

        // Add creation timestamp
        if (patient.getCreatedAt() != null) {
            properties.put("lifecyclestage", "lead");
            properties.put("hs_lead_status", "NEW");
        }

        return properties;
    }

    /**
     * Extract email from patient (generate if not available)
     */
    private String extractEmailFromPatient(Patient patient) {
        // If patient has an email, use it
        // Otherwise, generate a placeholder or use phone
        String phone = patient.getPhone() != null ? patient.getPhone() : "unknown";
        return "patient_" + patient.getId() + "@placeholder.local";
    }

    /**
     * Get HubSpot contact DTO from contact data
     */
    public HubSpotContactDto convertToContactDto(Patient patient) {
        String[] names = patient.getName() != null ? patient.getName().split(" ", 2) : new String[]{"", ""};
        return HubSpotContactDto.builder()
                .firstName(names[0])
                .lastName(names.length > 1 ? names[1] : "")
                .phone(patient.getPhone())
                .email(extractEmailFromPatient(patient))
                .build();
    }
}
