package com.ai.receptionist.repository;

import com.ai.receptionist.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    /** All active doctors — used by system jobs (SlotMaintenanceService) across all tenants */
    List<Doctor> findByActiveTrue();

    /** Active doctors for a specific tenant — used in user-facing call flows */
    List<Doctor> findByTenantIdAndActiveTrue(Long tenantId);

    /** Find doctor by key — global (kept for SlotMaintenanceService) */
    Optional<Doctor> findByKeyIgnoreCaseAndActiveTrue(String key);

    /** Find doctor by key scoped to a tenant — prevents cross-tenant booking */
    Optional<Doctor> findByKeyIgnoreCaseAndActiveTrueAndTenantId(String key, Long tenantId);
}
