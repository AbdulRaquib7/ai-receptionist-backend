package com.ai.receptionist.dto;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Structured flow outcome from booking/conversation logic.
 * No conversational text — only type and payload for deterministic voice rendering.
 */
public final class FlowResponse {

    public enum Type {
        ASK_SYMPTOM,
        ASK_NAME,
        ASK_PHONE,
        SUGGEST_DOCTOR,
        SUGGEST_SLOT,
        CONFIRM_BOOKING,
        CONFIRMED,
        CANCELLED,
        RESCHEDULED,
        REPEAT,
        GOODBYE,
        CLARIFY,
        SLOT_UNAVAILABLE,
        NO_APPOINTMENTS,
        ABORT_BOOKING,
        RETURN_TO_FLOW,
        OFFER_OTHER_DATES,
        MESSAGE,
        NONE
    }

    private final Type type;
    private final Map<String, Object> payload;
    private final boolean endCall;

    private FlowResponse(Type type, Map<String, Object> payload, boolean endCall) {
        this.type = type;
        this.payload = payload == null ? Collections.emptyMap() : new HashMap<>(payload);
        this.endCall = endCall;
    }

    public Type getType() {
        return type;
    }

    public Map<String, Object> getPayload() {
        return Collections.unmodifiableMap(payload);
    }

    public String getString(String key) {
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }

    public boolean isEndCall() {
        return endCall;
    }

    public static FlowResponse of(Type type) {
        return new FlowResponse(type, null, type == Type.GOODBYE);
    }

    public static FlowResponse of(Type type, Map<String, Object> payload) {
        return new FlowResponse(type, payload, type == Type.GOODBYE);
    }

    public static FlowResponse of(Type type, Map<String, Object> payload, boolean endCall) {
        return new FlowResponse(type, payload, endCall);
    }

    public static FlowResponse suggestDoctor(String doctorKey, String doctorName, String specialization) {
        Map<String, Object> p = new HashMap<>();
        p.put("doctorKey", doctorKey);
        p.put("doctorName", doctorName);
        p.put("specialization", specialization != null ? specialization : "");
        return new FlowResponse(Type.SUGGEST_DOCTOR, p, false);
    }

    public static FlowResponse suggestSlot(String doctorKey, String doctorName, String date, String time) {
        Map<String, Object> p = new HashMap<>();
        p.put("doctorKey", doctorKey);
        p.put("doctorName", doctorName);
        p.put("date", date);
        p.put("time", time);
        return new FlowResponse(Type.SUGGEST_SLOT, p, false);
    }

    public static FlowResponse confirmBooking(String doctorName, String date, String time) {
        Map<String, Object> p = new HashMap<>();
        p.put("doctorName", doctorName);
        p.put("date", date);
        p.put("time", time);
        return new FlowResponse(Type.CONFIRM_BOOKING, p, false);
    }

    public static FlowResponse confirmed(String date, String time) {
        Map<String, Object> p = new HashMap<>();
        p.put("date", date);
        p.put("time", time);
        return new FlowResponse(Type.CONFIRMED, p, false);
    }

    public static FlowResponse listDoctors(String doctorListText) {
        Map<String, Object> p = new HashMap<>();
        p.put("doctorList", doctorListText);
        return new FlowResponse(Type.SUGGEST_DOCTOR, p, false);
    }

    public static FlowResponse message(String messageKey) {
        Map<String, Object> p = new HashMap<>();
        p.put("messageKey", messageKey);
        return new FlowResponse(Type.MESSAGE, p, false);
    }

    public static FlowResponse offerOtherDates(String datesCsv) {
        Map<String, Object> p = new HashMap<>();
        p.put("dates", datesCsv);
        return new FlowResponse(Type.OFFER_OTHER_DATES, p, false);
    }
}
