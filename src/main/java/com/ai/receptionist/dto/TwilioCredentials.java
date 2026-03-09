package com.ai.receptionist.dto;

/**
 * Holds Twilio Account SID and Auth Token for a specific tenant.
 * Resolved at runtime: uses per-tenant credentials if configured,
 * otherwise falls back to the global environment variables.
 */
public record TwilioCredentials(String accountSid, String authToken) {

    /**
     * @return true if both SID and token are present and non-blank
     */
    public boolean isValid() {
        return accountSid != null && !accountSid.isBlank()
                && authToken != null && !authToken.isBlank();
    }
}
