package com.ai.receptionist.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for formatting available appointment time slots into human-readable
 * ranges (e.g. "6 PM to 9 PM") for inclusion in LLM context and voice responses.
 */
public final class SlotFormattingUtil {

    private SlotFormattingUtil() { /* utility class */ }

    /**
     * Converts a list of time strings (e.g. ["06:00 PM", "07:00 PM", "08:00 PM"])
     * into compact ranges: "6 PM to 8 PM". Non-consecutive times are listed separately.
     */
    public static String formatSlotsAsRanges(List<String> times) {
        if (times == null || times.isEmpty()) return "";
        List<Integer> minutesFromMidnight = new ArrayList<>();
        for (String t : times) {
            Integer m = parseTimeToMinutes(t);
            if (m != null) minutesFromMidnight.add(m);
        }
        if (minutesFromMidnight.isEmpty()) return times.toString();
        minutesFromMidnight.sort(Integer::compareTo);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < minutesFromMidnight.size()) {
            int start = minutesFromMidnight.get(i);
            int end = start;
            while (i + 1 < minutesFromMidnight.size() && minutesFromMidnight.get(i + 1) <= end + 35) {
                i++;
                end = minutesFromMidnight.get(i);
            }
            if (sb.length() > 0) sb.append(", ");
            if (start == end) {
                sb.append(minutesToDisplay(start));
            } else {
                sb.append(minutesToDisplay(start)).append(" to ").append(minutesToDisplay(end));
            }
            i++;
        }
        return sb.toString();
    }

    /**
     * Converts a list of time strings (e.g. ["06:00 PM", "06:30 PM"])
     * into a stable comma-separated list: "06:00 PM, 06:30 PM".
     *
     * This is intentionally NOT compressed into ranges because LLMs often pick
     * the first time in the range (e.g. "2:00 PM to 2:30 PM" → 2:00 PM).
     */
    public static String formatSlotsAsList(List<String> times) {
        if (times == null || times.isEmpty()) return "";
        return String.join(", ", times);
    }

    static Integer parseTimeToMinutes(String t) {
        if (t == null || t.isBlank()) return null;
        t = t.trim();
        boolean pm = t.toLowerCase().contains("pm") && !t.toLowerCase().contains("12:00 am");
        boolean am = t.toLowerCase().contains("am");
        String[] parts = t.replaceAll("(?i)(am|pm)", "").trim().split("[:.]");
        if (parts.length < 1) return null;
        int h = 0, m = 0;
        try {
            h = Integer.parseInt(parts[0].trim());
            if (parts.length >= 2) m = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (pm && h != 12) h += 12;
        if (am && h == 12) h = 0;
        return h * 60 + m;
    }

    static String minutesToDisplay(int mins) {
        int h = mins / 60;
        int m = mins % 60;
        if (h >= 12) {
            if (h > 12) h -= 12;
            return m > 0 ? String.format("%d:%02d PM", h, m) : String.format("%d PM", h);
        }
        if (h == 0) h = 12;
        return m > 0 ? String.format("%d:%02d AM", h, m) : String.format("%d AM", h);
    }
}
