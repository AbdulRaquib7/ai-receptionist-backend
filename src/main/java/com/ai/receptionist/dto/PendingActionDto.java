package com.ai.receptionist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-call pending action awaiting user confirmation.
 * Backend executes only when user confirms (e.g. says "yes").
 * Database is updated only after confirmation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingActionDto {

    public enum Intent {
        BOOK,
        CANCEL,
        RESCHEDULE
    }

    private Intent intent;
    private String doctorKey;
    private String date;
    private String time;
    private String patientName;
    private String patientPhone;
    /** For cancel/reschedule: which appointment (patient name or identifier). */
    private String targetPatientName;
    /** For reschedule: new slot. */
    private String newDate;
    private String newTime;

    @Builder.Default
    private boolean awaitingConfirmation = true;

    public boolean isAwaitingConfirmation() {
        return awaitingConfirmation;
    }
}
