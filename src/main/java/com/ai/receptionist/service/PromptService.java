package com.ai.receptionist.service;

import com.ai.receptionist.entity.PromptTemplate;
import com.ai.receptionist.repository.PromptTemplateRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Loads, caches, and renders prompt templates per tenant.
 * Templates support {{variable}} placeholders that are resolved at render time.
 */
@Service
@RequiredArgsConstructor
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);

    private final PromptTemplateRepository templateRepository;

    /** Cache key: "tenantId:templateKey" → template text */
    private final Cache<String, String> templateCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    /**
     * Get the raw template text for a tenant and key. Returns empty string if not found.
     */
    public String getTemplate(Long tenantId, String templateKey) {
        String cacheKey = tenantId + ":" + templateKey;
        return templateCache.get(cacheKey, k -> {
            Optional<PromptTemplate> opt = templateRepository
                    .findFirstByTenantIdAndTemplateKeyAndActiveTrueOrderByVersionDesc(tenantId, templateKey);
            if (opt.isEmpty()) {
                log.warn("No prompt template found for tenant={} key={}", tenantId, templateKey);
                return "";
            }
            return opt.get().getTemplateText();
        });
    }

    /**
     * Get template text with a fallback if no template exists in DB.
     */
    public String getTemplate(Long tenantId, String templateKey, String fallback) {
        String template = getTemplate(tenantId, templateKey);
        return template.isEmpty() ? fallback : template;
    }

    /**
     * Render a template with {{variable}} placeholder substitution.
     * Variables map keys should NOT include the {{ }} delimiters.
     */
    public String renderTemplate(Long tenantId, String templateKey, Map<String, String> variables) {
        String template = getTemplate(tenantId, templateKey);
        return renderText(template, variables);
    }

    /**
     * Render a template with fallback, then apply {{variable}} substitution.
     */
    public String renderTemplate(Long tenantId, String templateKey, String fallback, Map<String, String> variables) {
        String template = getTemplate(tenantId, templateKey, fallback);
        return renderText(template, variables);
    }

    /**
     * Apply {{variable}} substitution to raw text.
     */
    public static String renderText(String text, Map<String, String> variables) {
        if (text == null || text.isEmpty() || variables == null || variables.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }
}
