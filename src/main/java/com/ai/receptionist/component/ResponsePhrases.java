package com.ai.receptionist.component;

import com.ai.receptionist.service.PromptService;
import com.ai.receptionist.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides response phrases for the voice flow.
 * Template-driven phrases (greeting, farewell, errors) are loaded from the DB via PromptService.
 * Static phrases remain hardcoded — they are only used by ConfirmationExecutionService
 * and don't vary by tenant in the current design.
 */
@Component
@RequiredArgsConstructor
public class ResponsePhrases {

    private final PromptService promptService;
    private final TenantService tenantService;

    /**
     * Tenant-specific greeting loaded from prompt_template table.
     */
    public String greeting(Long tenantId) {
        Map<String, String> vars = buildVars(tenantId);
        return promptService.renderTemplate(tenantId, "greeting",
                "Hey! Thanks for calling. What can I help you with? You can book, reschedule, or cancel an appointment.",
                vars);
    }

    /** Backward-compatible default greeting (for cases where tenantId isn't available yet). */
    public String greeting() {
        return "Hey! Thanks for calling. What can I help you with? You can book, reschedule, or cancel an appointment.";
    }

    public String farewell(Long tenantId) {
        Map<String, String> vars = buildVars(tenantId);
        return promptService.renderTemplate(tenantId, "farewell",
                "Alright, have a good day! Bye.", vars);
    }

    public String errorFallback(Long tenantId) {
        return promptService.getTemplate(tenantId, "error_fallback",
                "I'm having a quick technical moment. Could you repeat that?");
    }

    public String unclearInput(Long tenantId) {
        return promptService.getTemplate(tenantId, "unclear_input",
                "Sorry, I didn't catch that. Could you repeat?");
    }

    // --- Static phrases (not tenant-configurable yet) ---

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
        return "Alright, it's cancelled. Is there anything else I can help you with?";
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

    private Map<String, String> buildVars(Long tenantId) {
        Map<String, String> vars = new HashMap<>();
        if (tenantId != null) {
            String businessName = tenantService.getTenantName(tenantId);
            vars.put("business_name", businessName != null ? businessName : "our office");
            vars.put("ai_name", tenantService.getConfig(tenantId, "ai_name", "Sarah"));
            vars.put("supported_actions", tenantService.getConfig(tenantId, "supported_actions",
                    "book, reschedule, or cancel appointments"));
        } else {
            vars.put("business_name", "our office");
            vars.put("ai_name", "Sarah");
            vars.put("supported_actions", "book, reschedule, or cancel appointments");
        }
        return vars;
    }
}
