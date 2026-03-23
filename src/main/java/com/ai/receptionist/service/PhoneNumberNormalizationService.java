package com.ai.receptionist.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Normalizes user-provided phone numbers to E.164 format when possible.
 *
 * Rules:
 * - If number already starts with '+', we keep it (after basic sanitization).
 * - If number is digits-only (no '+'), we try to infer the country code from the
 *   caller's E.164 number.
 * - If we can't infer safely, we return Optional.empty so the caller can ask the
 *   user for the country code (no hardcoding).
 */
@Service
public class PhoneNumberNormalizationService {

    public Optional<String> normalizeToE164(String rawPhone, String callerPhoneE164) {
        if (StringUtils.isBlank(rawPhone)) {
            return Optional.empty();
        }

        String cleaned = rawPhone.trim();
        // Allow + and digits; strip spaces/dashes/parentheses.
        cleaned = cleaned.replaceAll("[\\s\\-()]", "");

        if (cleaned.startsWith("+")) {
            String digits = digitsOnly(cleaned.substring(1));
            if (digits.length() < 8) return Optional.empty();
            return Optional.of("+" + digits);
        }

        // Handle international prefix 00 (e.g. 0044...)
        if (cleaned.startsWith("00")) {
            String digits = digitsOnly(cleaned.substring(2));
            if (digits.length() < 8) return Optional.empty();
            return Optional.of("+" + digits);
        }

        String patientDigits = digitsOnly(cleaned);
        if (patientDigits.length() < 8) {
            return Optional.empty();
        }

        // Infer country code from caller E.164 (e.g. +1415xxxxxxxx).
        if (StringUtils.isBlank(callerPhoneE164) || !callerPhoneE164.trim().startsWith("+")) {
            return Optional.empty();
        }

        String callerDigits = digitsOnly(callerPhoneE164.trim().substring(1));
        if (callerDigits.length() <= 10) {
            // Can't reliably infer country code; caller might itself be unverified/national.
            return Optional.empty();
        }

        // Heuristic: assume the last 10 digits are the national number
        // (works for many common formats; otherwise we ask user).
        int nationalLen = 10;
        int countryLen = callerDigits.length() - nationalLen;
        if (countryLen < 1 || countryLen > 3) {
            return Optional.empty();
        }

        // Only auto-normalize when patient looks like a 10-digit national number.
        if (patientDigits.length() != nationalLen) {
            return Optional.empty();
        }

        String countryCode = callerDigits.substring(0, countryLen);
        return Optional.of("+" + countryCode + patientDigits);
    }

    private static String digitsOnly(String s) {
        if (s == null) return "";
        return s.replaceAll("[^0-9]", "");
    }
}

