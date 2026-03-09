package com.ai.receptionist.service;

import com.ai.receptionist.dto.TwilioCredentials;
import com.ai.receptionist.entity.Tenant;
import com.ai.receptionist.entity.TenantConfig;
import com.ai.receptionist.repository.TenantConfigRepository;
import com.ai.receptionist.repository.TenantRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Loads and caches tenant configuration.
 * Caches are TTL-bounded so DB changes propagate without restart.
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final TenantRepository tenantRepository;
    private final TenantConfigRepository tenantConfigRepository;
    private final String globalAccountSid;
    private final String globalAuthToken;

    public TenantService(TenantRepository tenantRepository,
                         TenantConfigRepository tenantConfigRepository,
                         @Value("${twilio.account-sid}") String globalAccountSid,
                         @Value("${twilio.auth-token}") String globalAuthToken) {
        this.tenantRepository = tenantRepository;
        this.tenantConfigRepository = tenantConfigRepository;
        this.globalAccountSid = globalAccountSid;
        this.globalAuthToken = globalAuthToken;
    }

    /** Cache tenant lookups by Twilio phone number */
    private final Cache<String, Optional<Tenant>> tenantByPhone = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    /** Cache tenant config maps by tenantId */
    private final Cache<Long, Map<String, String>> configCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    /**
     * Resolve tenant by the Twilio phone number that received the call (the "To" number).
     * Falls back to the "default" tenant if no match found.
     */
    public Tenant resolveTenant(String twilioPhone) {
        Optional<Tenant> cached = tenantByPhone.get(twilioPhone,
                phone -> tenantRepository.findByTwilioPhoneAndActiveTrue(phone));
        if (cached != null && cached.isPresent()) {
            return cached.get();
        }
        // Fallback to default tenant
        return tenantRepository.findBySlugAndActiveTrue("default")
                .orElseThrow(() -> new IllegalStateException("Default tenant not found. Run migrations."));
    }

    /**
     * Resolve Twilio credentials for a tenant.
     * If the tenant has its own twilio_account_sid and twilio_auth_token → use those.
     * Otherwise → fall back to the global environment variables.
     */
    public TwilioCredentials getTwilioCredentials(Long tenantId) {
        if (tenantId != null) {
            Optional<Tenant> tenant = tenantRepository.findById(tenantId);
            if (tenant.isPresent()) {
                Tenant t = tenant.get();
                if (t.getTwilioAccountSid() != null && !t.getTwilioAccountSid().isBlank()
                        && t.getTwilioAuthToken() != null && !t.getTwilioAuthToken().isBlank()) {
                    log.debug("Using per-tenant Twilio credentials for tenantId={}", tenantId);
                    return new TwilioCredentials(t.getTwilioAccountSid(), t.getTwilioAuthToken());
                }
            }
        }
        // Fallback to global credentials
        return new TwilioCredentials(globalAccountSid, globalAuthToken);
    }

    /**
     * Resolve Twilio credentials for signature validation by the "To" phone number.
     * Used by the signature filter before tenantId is known.
     */
    public TwilioCredentials getTwilioCredentialsByPhone(String twilioPhone) {
        if (twilioPhone != null && !twilioPhone.isBlank()) {
            Optional<Tenant> cached = tenantByPhone.get(twilioPhone,
                    phone -> tenantRepository.findByTwilioPhoneAndActiveTrue(phone));
            if (cached != null && cached.isPresent()) {
                Tenant t = cached.get();
                if (t.getTwilioAccountSid() != null && !t.getTwilioAccountSid().isBlank()
                        && t.getTwilioAuthToken() != null && !t.getTwilioAuthToken().isBlank()) {
                    return new TwilioCredentials(t.getTwilioAccountSid(), t.getTwilioAuthToken());
                }
            }
        }
        return new TwilioCredentials(globalAccountSid, globalAuthToken);
    }

    /**
     * Get a single config value for a tenant, with a default fallback.
     */
    public String getConfig(Long tenantId, String key, String defaultValue) {
        Map<String, String> config = getAllConfig(tenantId);
        return config.getOrDefault(key, defaultValue);
    }

    /**
     * Get all config key-value pairs for a tenant.
     */
    public Map<String, String> getAllConfig(Long tenantId) {
        return configCache.get(tenantId, id -> {
            List<TenantConfig> configs = tenantConfigRepository.findByTenantId(id);
            return configs.stream()
                    .collect(Collectors.toMap(TenantConfig::getConfigKey, TenantConfig::getConfigValue));
        });
    }

    /**
     * Get tenant name by ID. Returns null if not found.
     */
    public String getTenantName(Long tenantId) {
        if (tenantId == null) return null;
        return tenantRepository.findById(tenantId).map(Tenant::getName).orElse(null);
    }

    /**
     * Get farewell phrases for a tenant. Returns configurable list or sensible defaults.
     */
    public List<String> getFarewellPhrases(Long tenantId) {
        String phrases = getConfig(tenantId, "farewell_phrases", "");
        if (phrases.isBlank()) {
            return List.of("have a good day", "have a great day", "thanks for calling",
                    "thank you for calling", "take care", "goodbye", "bye");
        }
        return Arrays.stream(phrases.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
