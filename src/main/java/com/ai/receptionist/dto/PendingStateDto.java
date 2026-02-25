package com.ai.receptionist.dto;

import java.io.Serializable;

import com.ai.receptionist.utils.ConversationState;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class PendingStateDto implements Serializable {

    public String doctorKey;
    public String date;
    public String time;

    public String patientName;
    public String patientPhone;
    public boolean pendingNeedNamePhone;

    public boolean pendingConfirmBook;
    public boolean pendingCancel;

    public String cancelPatientName;
    public boolean pendingReschedule;

    public String reschedulePatientName;
    public String rescheduleDoctorKey;
    public String rescheduleDate;
    public String rescheduleTime;

    public String lastSuggestedDoctorKey;
    public String lastSuggestedDate;
    public String lastSuggestedTime;

    public boolean bookingLocked;
    public boolean bookingCompleted;

    public ConversationState currentState = ConversationState.START;

    public boolean hasAnyPending() {
        return pendingNeedNamePhone
                || pendingConfirmBook
                || pendingCancel
                || pendingReschedule;
    }

    public void clearFlowFlags() {
        pendingNeedNamePhone = false;
        pendingConfirmBook = false;
        pendingCancel = false;
        pendingReschedule = false;
    }
}