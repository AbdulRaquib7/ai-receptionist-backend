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
	
	public boolean pendingConfirmAbort;
		
	public String cancelPatientName;
	
	public String reschedulePatientName;
	
	public String rescheduleDoctorKey;
	
	public String rescheduleDate;
	
	public String rescheduleTime;
	
	public ConversationState currentState = ConversationState.START;
	
	public boolean bookingLocked;
	
	public boolean bookingCompleted;

	/**
	 * True when the caller has explicitly asked to end the call
	 * (e.g. said "bye", "end call", "stop"), and we have asked
	 * them to confirm that they really want to hang up.
	 */
	public boolean pendingConfirmEndCall;

	/**
	 * Latched flag that the user has indicated they want to end
	 * the conversation at some point (used so MediaStreamHandler
	 * can safely hang up only after a friendly goodbye has been
	 * spoken back to the user).
	 */
	public boolean userRequestedEnd;

	/**
	 * True when the user temporarily diverted from the main flow
	 * (e.g. asked a general question like weather/doctor info)
	 * and we should resume the previous booking/cancel/reschedule
	 * flow once the interruption is handled.
	 */
	public boolean interruptionContext;
	
    public boolean hasAnyPending() {
        return pendingConfirmBook || pendingNeedNamePhone || pendingConfirmCancel
                || pendingConfirmReschedule || pendingRescheduleDetails
				|| pendingChooseCancelAppointment || pendingChooseRescheduleAppointment
				|| pendingConfirmAbort || pendingConfirmEndCall;
    }

}
