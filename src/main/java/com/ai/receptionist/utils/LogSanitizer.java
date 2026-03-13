package com.ai.receptionist.utils;

/**
 * Utility for masking PII (Personally Identifiable Information) in log output.
 * Masks phone numbers, names, and free-text conversation content so that
 * production logs never contain raw PII.
 */
public final class LogSanitizer {

    private LogSanitizer() { /* utility class */ }

    /**
     * Masks a phone number, keeping only the last 4 digits visible.
     * Examples:
     *   "+14155551234" → "***1234"
     *   "anonymous"    → "anonymous"
     *   null / blank   → "(none)"
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return "(none)";
        String stripped = phone.replaceAll("[^0-9]", "");
        if (stripped.length() < 4) return "***";
        return "***" + stripped.substring(stripped.length() - 4);
    }

    /**
     * Masks a person's name, keeping only the first character visible.
     * Examples:
     *   "John Smith" → "J***"
     *   null / blank → "(none)"
     */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) return "(none)";
        return name.charAt(0) + "***";
    }

    /**
     * Truncates free-text content (user speech, AI replies) for safe logging.
     * Shows at most the first 20 characters followed by "…[N chars]".
     * Examples:
     *   "Hello, I'd like to book an appointment" → "Hello, I'd like to b…[38 chars]"
     *   null / blank → "(empty)"
     */
    public static String truncateText(String text) {
        if (text == null || text.isBlank()) return "(empty)";
        if (text.length() <= 20) return text;
        return text.substring(0, 20) + "…[" + text.length() + " chars]";
    }
}
