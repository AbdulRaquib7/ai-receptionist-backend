package com.ai.receptionist.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * HubSpot Configuration
 * Credentials are read from application.properties
 * RestTemplate and ObjectMapper are already provided by Spring Boot
 */
@Configuration
public class HubSpotConfig {

    @Value("${hubspot.api-key}")
    private String apiKey;

    @Value("${hubspot.api-base-url}")
    private String apiBaseUrl;

    public String getApiKey() {
        return apiKey;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }
}
