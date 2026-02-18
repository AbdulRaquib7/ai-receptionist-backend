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
	
	public boolean pendingConfirmBook;
	
	public boolean pendingNeedNamePhone;
	
	public boolean pendingConfirmCancel;
	
	public boolean pendingConfirmReschedule;
	
	public boolean pendingRescheduleDetails;
	
	public boolean pendingChooseCancelAppointment;
	
	public boolean pendingChooseRescheduleAppointment;
	
	public String lastSuggestedDoctorKey;
	
	public String lastSuggestedDate;
	
	public boolean pendingConfirmAbort;
		
	public String cancelPatientName;
	
	public String reschedulePatientName;
	
	public String rescheduleDoctorKey;
	
	public String rescheduleDate;
	
	public String rescheduleTime;
	
	public ConversationState currentState = ConversationState.START;
	
	public boolean bookingLocked;
	
	public boolean bookingCompleted;
	
	public boolean hasAnyPending() {
	    return pendingConfirmBook
	            || pendingNeedNamePhone
	            || pendingConfirmCancel
	            || pendingConfirmReschedule
	            || pendingRescheduleDetails
	            || pendingChooseCancelAppointment
	            || pendingChooseRescheduleAppointment
	            || pendingConfirmAbort;
	}

	public void clearFlowFlags() {
	    pendingConfirmBook = false;
	    pendingNeedNamePhone = false;
	    pendingConfirmCancel = false;
	    pendingConfirmReschedule = false;
	    pendingRescheduleDetails = false;
	    pendingChooseCancelAppointment = false;
	    pendingChooseRescheduleAppointment = false;
	    pendingConfirmAbort = false;
	}


}
