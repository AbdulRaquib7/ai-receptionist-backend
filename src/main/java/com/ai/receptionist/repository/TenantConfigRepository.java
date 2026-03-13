package com.ai.receptionist.repository;

import com.ai.receptionist.entity.TenantConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantConfigRepository extends JpaRepository<TenantConfig, Long> {

    List<TenantConfig> findByTenantId(Long tenantId);

    Optional<TenantConfig> findByTenantIdAndConfigKey(Long tenantId, String configKey);
}
