package com.ai.receptionist.service;

import org.springframework.stereotype.Component;

/**
 * Human-like response phrases. Used when flow logic generates replies.
 * Phrases are conversational, warm, and non-robotic.
 */
@Component
public class ResponsePhrases {

    public String greeting() {
        return "Hey! Thanks for calling. What can I help you with? You can book, reschedule, or cancel an appointment.";
    }

    public String confirmBookingPrompt(String doctorName, String date, String time) {
        return "Alright, so that's " + doctorName + " on " + date + " at " + time + ". Want me to confirm that?";
    }

    public String bookingConfirmed() {
        return "Awesome! You're all set. You'll get a reminder too. Anything else I can help with?";
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
        return "Sure. What's your name and phone number for the appointment?";
    }

    public String noChangesAbortChoice() {
        return "No problem, no changes made. Do you want to try something else, or should I end the call?";
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
        return "Hey, are you still there?";
    }

    public String sorryFixIt() {
        return "I'm really sorry about that. Let's fix it.";
    }
}
