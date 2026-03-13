package com.ai.receptionist.repository;

import com.ai.receptionist.entity.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {

    /** Get the latest active version of a template for a tenant. */
    Optional<PromptTemplate> findFirstByTenantIdAndTemplateKeyAndActiveTrueOrderByVersionDesc(
            Long tenantId, String templateKey);

    /** Get all active templates for a tenant. */
    List<PromptTemplate> findByTenantIdAndActiveTrue(Long tenantId);
}
