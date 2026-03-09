package com.ai.receptionist.repository;

import com.ai.receptionist.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    Optional<Patient> findByTwilioPhone(String twilioPhone);

    /** Global lookup — kept for backward compat */
    Optional<Patient> findFirstByTwilioPhoneAndNameIgnoreCase(String twilioPhone, String name);

    /** Tenant-scoped patient lookup — prevents cross-tenant patient reuse */
    Optional<Patient> findFirstByTwilioPhoneAndNameIgnoreCaseAndTenantId(
            String twilioPhone, String name, Long tenantId);
}
