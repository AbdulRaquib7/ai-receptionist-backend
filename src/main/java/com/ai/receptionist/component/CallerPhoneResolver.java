package com.ai.receptionist.component;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for resolving a caller's phone number.
 * Handles anonymous/test callers (Twilio test client, missing number, etc.)
 * by returning a configurable fallback number.
 */
@Component
public class CallerPhoneResolver {

    private static final String DEFAULT_ANONYMOUS_CALLER = "+100000000";

    @Value("${caller.anonymous-fallback:+100000000}")
    private String anonymousCallerFallback;

    /**
     * Resolves the caller's phone number for DB lookups.
     * Returns the fallback for test/anonymous callers so all flows
     * (book, cancel, reschedule) share the same synthetic caller number.
     */
    public String resolve(String fromNumber) {
        if (fromNumber == null || fromNumber.isBlank()
                || fromNumber.startsWith("client:")
                || "anonymous".equalsIgnoreCase(fromNumber.trim())
                || "unknown".equalsIgnoreCase(fromNumber.trim())) {
            return StringUtils.isNotBlank(anonymousCallerFallback)
                    ? anonymousCallerFallback.trim()
                    : DEFAULT_ANONYMOUS_CALLER;
        }
        return fromNumber;
    }
}
