package com.ai.receptionist.repository;

import com.ai.receptionist.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByTwilioPhoneAndActiveTrue(String twilioPhone);

    Optional<Tenant> findBySlugAndActiveTrue(String slug);

    Optional<Tenant> findBySlug(String slug);

	List<Tenant> findByActiveTrue();
}
