package com.ai.receptionist.component;

import org.springframework.stereotype.Component;

@Component
public class ResponsePhrases {

    public String greeting() {
        return "Hey! Thanks for calling. What can I help you with? You can book, reschedule, or cancel an appointment.";
    }

    public String confirmBookingPrompt(String doctorName, String date, String time) {
        return "Okay, you're booking with " + doctorName +
               " on " + date + " at " + time +
               ". Should I confirm the appointment now?";
    }

    public String confirmAfterDoctorInfo() {
        return "Would you like me to confirm the appointment now?";
    }

    public String bookingConfirmed() {
        return "Awesome! You're all set. You'll get a reminder too. Can I help you with anything else?";
    }

    public String bookingConfirmedWithDetails(String date, String time) {
        return "You're all set! Your appointment is confirmed for " + date + " at " + time + ". Can I help you with anything else?";
    }

    public String slotUnavailable() {
        return "Hmm, that slot's taken. Want to try a different time?";
    }

    public String noAppointmentsToCancel() {
        return "You don't have any appointments to cancel.";
    }

    public String confirmCancelPrompt(String patientName, String doctorName, String date, String time) {
        return "Got it. So you want to cancel " + patientName + "'s appointment with " + doctorName + " on " + date + " at " + time + ". Just to confirm — should I go ahead?";
    }

    public String cancelConfirmed() {
        return "Alright, it's cancelled. Let us know if you need anything else.";
    }

    public String noAppointmentsToReschedule() {
        return "You don't have any appointments to reschedule.";
    }

    public String reschedulePromptNewSlot(String doctorName, String date, String time) {
        return "Okay, so we're moving it to " + doctorName + " on " + date + " at " + time + ". Confirm?";
    }

    public String rescheduleConfirmed() {
        return "Done! Your appointment's been rescheduled. Anything else?";
    }

    public String needNameAndPhone() {
        return "Got it. May I have your name and phone number for the appointment?";
    }

    public String noChangesAbortChoice() {
        return "No problem, no changes made. Do you want to try something else, or should I end the call?";
    }

    public String abortBooking() {
        return "No problem, I've stopped the booking. Is there anything else I can help with?";
    }

    public String startOver() {
        return "Sure thing! What can I help you with?";
    }

    public String goodbye() {
        return "Alright, have a good day! Bye.";
    }

    public String callLater() {
        return "Sure! No worries. We'll be here. Have a good day!";
    }

    public String stillThere() {
        return "Are you still there?";
    }

    public String stillHere() {
        return "I'm still here — please go ahead.";
    }

    public String sorryFixIt() {
        return "I'm really sorry about that. Let's fix it.";
    }

    public String couldYouRepeat() {
        return "Sorry, I didn't catch that. Could you repeat?";
    }

    public String unclearAskAgain() {
        return "I'm sorry, I didn't catch that clearly. Could you please repeat in English?";
    }

    public String clarifyWhatHappened() {
        return "I can check your appointments or help you book or cancel. Say 'my appointments' to hear your nearest one, or tell me what you'd like to do.";
    }

    public String noOtherDates() {
        return "That's the only available date.";
    }

    public String noSlots() {
        return "No slots available right now.";
    }

    public String whichDoctor() {
        return "Which doctor would you like me to check?";
    }

    public String doctorNoAvailability() {
        return "That doctor doesn't have availability right now.";
    }

    public String noAvailableSlotsFound() {
        return "No available slots found.";
    }

    public String differentTime() {
        return "Okay, no problem. Would you like a different time?";
    }

    public String continueBooking() {
        return "Sure. And about your appointment — shall we continue?";
    }

    public String abortBookingAck() {
        return "No problem. I've stopped the booking. Let me know if you'd like to start again.";
    }

    public String confirmBookSuccessShort(String date, String time) {
        return "You're all set! Your appointment is confirmed for " + date + " at " + time + ". We'll see you then. Take care!";
    }

    public String tryAgainDetails() {
        return "I don't have the full booking details. Let's try again.";
    }

    public String couldntCancel() {
        return "Couldn't cancel that. Want to try again?";
    }

    public String tryAgainNewSlot() {
        return "I don't have the new slot. Let's try again.";
    }

    public String nearestSlotIs(String date, String time) {
        return "The nearest available slot is " + date + " at " + time + ". Would that work?";
    }

    public String weAlsoHave(String datesList) {
        return "Sure — we also have " + datesList + ". Which works for you?";
    }

    public String doctorIntroThenSlot(String doctorName, String spec, String date, String time) {
        return doctorName + " is our " + spec + ". The nearest available slot is " + date + " at " + time + ". Would that work?";
    }

    public String noProblemHowElse() {
        return "No problem. How else can I help you?";
    }

    public String sureWhatWouldYouLike() {
        return "Sure. What would you like to do?";
    }

    public String noDoctorsAvailable() {
        return "We don't have any doctors available at the moment.";
    }
}
