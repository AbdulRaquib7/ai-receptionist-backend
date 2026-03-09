package com.ai.receptionist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Immutable-ish DTO for pending actions. Only {@code awaitingConfirmation} can be mutated
 * after construction (via {@link #setAwaitingConfirmation}). All other fields are set
 * via the builder and should not change once created.
 */
@Getter
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
    
    private String targetPatientName;

    private String newDate;
    
    private String newTime;

    @Builder.Default
    private boolean awaitingConfirmation = true;

    public boolean isAwaitingConfirmation() {
        return awaitingConfirmation;
    }

    public void setAwaitingConfirmation(boolean awaitingConfirmation) {
        this.awaitingConfirmation = awaitingConfirmation;
    }
}
