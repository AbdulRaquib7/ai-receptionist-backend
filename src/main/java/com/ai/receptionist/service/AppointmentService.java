package com.ai.receptionist.service;

import com.ai.receptionist.config.ConversationProperties;
import com.ai.receptionist.entity.*;
import com.ai.receptionist.repository.*;
import com.ai.receptionist.service.hubspot.HubSpotSyncOrchestrator;
import com.ai.receptionist.utils.LogSanitizer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final DoctorRepository doctorRepository;
    private final AppointmentSlotRepository slotRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final ConversationProperties conversationProps;
    private final HubSpotSyncOrchestrator hubSpotSyncOrchestrator;


    /** Get all active doctors for a specific tenant */
    public List<Doctor> getAllDoctors(Long tenantId) {
        return doctorRepository.findByTenantIdAndActiveTrue(tenantId);
    }

    /** Get available slots for the next week, scoped to a tenant */
    public Map<String, Map<String, List<String>>> getAvailableSlotsForNextWeek(Long tenantId) {

        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(conversationProps.getSlotLookAheadDays());

        List<AppointmentSlot> allSlots = slotRepository.findAllAvailableSlotsByTenantId(
                tenantId, today, end, AppointmentSlot.Status.AVAILABLE);

        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();

        for (AppointmentSlot slot : allSlots) {
            String doctorKey = slot.getDoctor().getKey();
            String date = slot.getSlotDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
            result.computeIfAbsent(doctorKey, k -> new LinkedHashMap<>())
                    .computeIfAbsent(date, k -> new ArrayList<>())
                    .add(slot.getStartTime());
        }

        return result;
    }

    public Optional<Appointment> getActiveAppointmentByTwilioPhone(String twilioPhone) {
        return appointmentRepository
                .findFirstByPatient_TwilioPhoneAndStatusOrderByCreatedAtDesc(
                        twilioPhone,
                        Appointment.Status.CONFIRMED
                );
    }

    public Optional<Appointment> getActiveAppointmentByTwilioPhoneAndPatientName(String twilioPhone, String patientName) {
        if (patientName == null || patientName.isBlank()) return getActiveAppointmentByTwilioPhone(twilioPhone);
        return appointmentRepository
                .findFirstByPatient_TwilioPhoneAndPatient_NameIgnoreCaseAndStatusOrderByCreatedAtDesc(
                        twilioPhone, patientName.trim(), Appointment.Status.CONFIRMED);
    }

    /** Confirmed appointment summaries scoped to a tenant */
    @Transactional(readOnly = true)
    public List<AppointmentSummary> getActiveAppointmentSummaries(String twilioPhone, Long tenantId) {
        List<Appointment> list = appointmentRepository
                .findConfirmedByPhoneAndTenantWithDetails(twilioPhone, tenantId);
        return list.stream()
                .map(a -> new AppointmentSummary(
                        a.getPatient().getName(),
                        a.getDoctor().getName(),
                        a.getDoctor().getKey(),
                        a.getSlot().getSlotDate().toString(),
                        a.getSlot().getStartTime()
                ))
                .collect(Collectors.toList());
    }

    /** Upcoming appointments only (slot date >= today), scoped to a tenant */
    @Transactional(readOnly = true)
    public List<AppointmentSummary> getUpcomingAppointmentSummaries(String twilioPhone, Long tenantId) {
        LocalDate today = LocalDate.now();
        return getActiveAppointmentSummaries(twilioPhone, tenantId).stream()
                .filter(a -> a.slotDate != null && !a.slotDate.isBlank())
                .filter(a -> {
                    try {
                        return !LocalDate.parse(a.slotDate).isBefore(today);
                    } catch (Exception e) {
                        return true;
                    }
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<AppointmentSummary> getActiveAppointmentSummary(String twilioPhone, Long tenantId) {
        List<AppointmentSummary> list = getActiveAppointmentSummaries(twilioPhone, tenantId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Transactional(readOnly = true)
    public Optional<AppointmentSummary> getActiveAppointmentSummary(String twilioPhone, String patientName, Long tenantId) {
        if (patientName == null || patientName.isBlank()) return getActiveAppointmentSummary(twilioPhone, tenantId);
        List<Appointment> list = appointmentRepository
                .findConfirmedByPhoneAndTenantWithDetails(twilioPhone, tenantId);
        return list.stream()
                .filter(a -> a.getPatient() != null && a.getPatient().getName() != null
                        && a.getPatient().getName().equalsIgnoreCase(patientName.trim()))
                .findFirst()
                .map(a -> new AppointmentSummary(
                        a.getPatient().getName(),
                        a.getDoctor().getName(),
                        a.getDoctor().getKey(),
                        a.getSlot().getSlotDate().toString(),
                        a.getSlot().getStartTime()));
    }

    /** Upcoming appointment by patient name, scoped to a tenant */
    @Transactional(readOnly = true)
    public Optional<AppointmentSummary> getUpcomingAppointmentSummary(String twilioPhone, String patientName, Long tenantId) {
        List<AppointmentSummary> upcoming = getUpcomingAppointmentSummaries(twilioPhone, tenantId);
        if (patientName == null || patientName.isBlank()) {
            return upcoming.isEmpty() ? Optional.empty() : Optional.of(upcoming.get(0));
        }
        String nameLower = patientName.trim().toLowerCase();
        Optional<AppointmentSummary> exact = upcoming.stream()
                .filter(a -> a.patientName != null && a.patientName.trim().equalsIgnoreCase(patientName.trim()))
                .findFirst();
        if (exact.isPresent()) return exact;
        return upcoming.stream()
                .filter(a -> a.patientName != null && (a.patientName.toLowerCase().contains(nameLower) || nameLower.contains(a.patientName.toLowerCase())))
                .findFirst();
    }

    public static class AppointmentSummary {
        public final String patientName;
        public final String doctorName;
        public final String doctorKey;
        public final String slotDate;
        public final String startTime;

        public AppointmentSummary(String patientName, String doctorName, String doctorKey, String slotDate, String startTime) {
            this.patientName = patientName;
            this.doctorName = doctorName;
            this.doctorKey = doctorKey;
            this.slotDate = slotDate;
            this.startTime = startTime;
        }
    }

    @Transactional
    public Optional<Appointment> bookAppointment(
            Long tenantId,
            String twilioPhone,
            String patientName,
            String patientPhone,
            String doctorKey,
            String date,
            String time
    ) {

        // Input validation
        if (patientName != null && patientName.length() > 100) {
            log.warn("Patient name too long ({} chars), truncating", patientName.length());
            patientName = patientName.substring(0, 100);
        }
        if (patientPhone != null && !patientPhone.matches("[+\\d\\s\\-()]{0,20}")) {
            log.warn("Invalid patient phone format: {}", LogSanitizer.maskPhone(patientPhone));
            return Optional.empty();
        }

        // Tenant-scoped doctor lookup
        Doctor doctor =
                doctorRepository
                        .findByKeyIgnoreCaseAndActiveTrueAndTenantId(doctorKey, tenantId)
                        .orElse(null);

        if (doctor == null) {
            log.warn("Doctor not found: {} for tenant {}", doctorKey, tenantId);
            return Optional.empty();
        }

        LocalDate slotDate;
        try {
            slotDate = LocalDate.parse(date);
        } catch (Exception e) {
            log.warn("Invalid date: {}", date);
            return Optional.empty();
        }

        Optional<AppointmentSlot> slotOpt =
                slotRepository.findByDoctorIdAndSlotDateAndStartTimeAndStatus(
                        doctor.getId(),
                        slotDate,
                        normalizeTime(time),
                        AppointmentSlot.Status.AVAILABLE
                );

        if (!slotOpt.isPresent()) {
            log.warn("Slot not available: doctor={} date={} time={}", doctorKey, date, time);
            return Optional.empty();
        }

        String name = StringUtils.hasText(patientName) ? patientName : "Unknown";
        // Tenant-scoped patient lookup
        Patient patient = patientRepository
                .findFirstByTwilioPhoneAndNameIgnoreCaseAndTenantId(twilioPhone, name, tenantId)
                .orElse(null);
        if (patient == null) {
            patient = Patient.builder()
                    .name(name)
                    .phone(StringUtils.hasText(patientPhone) ? patientPhone : twilioPhone)
                    .twilioPhone(twilioPhone)
                    .tenantId(tenantId)
                    .build();
        } else if (StringUtils.hasText(patientPhone)) {
            patient.setPhone(patientPhone);
        }
        patient = patientRepository.save(patient);

        AppointmentSlot slot = slotOpt.get();
        slot.setStatus(AppointmentSlot.Status.BOOKED);
        slotRepository.save(slot);

        Appointment appointment = Appointment.builder()
                .patient(patient)
                .doctor(doctor)
                .slot(slot)
                .status(Appointment.Status.CONFIRMED)
                .tenantId(tenantId)
                .build();

        appointment = appointmentRepository.save(appointment);

        log.info(
                "Booked appointment | apptId={} patientId={} name={} phone={} doctor={} date={} time={} tenant={}",
                appointment.getId(),
                patient.getId(),
                LogSanitizer.maskName(patient.getName()),
                LogSanitizer.maskPhone(patient.getPhone()),
                doctor.getName(),
                date,
                time,
                tenantId
        );

        // Sync appointment to HubSpot CRM (non-blocking)
        try {
            hubSpotSyncOrchestrator.syncNewAppointment(appointment);
        } catch (Exception e) {
            log.warn("Failed to sync appointment to HubSpot, but appointment booking succeeded", e);
        }

        return Optional.of(appointment);
    }


    @Transactional
    public boolean cancelAppointment(Long tenantId, String twilioPhone) {
        return cancelAppointment(tenantId, twilioPhone, null);
    }

    @Transactional
    public boolean cancelAppointment(Long tenantId, String twilioPhone, String patientName) {
        Optional<Appointment> opt = getUpcomingAppointmentEntity(tenantId, twilioPhone, patientName);
        if (!opt.isPresent()) return false;

        Appointment appt = opt.get();
        appt.setStatus(Appointment.Status.CANCELLED);
        appointmentRepository.save(appt);

        AppointmentSlot slot = appt.getSlot();
        slot.setStatus(AppointmentSlot.Status.AVAILABLE);
        slotRepository.save(slot);

        log.info("Cancelled appointment for {} ({})", LogSanitizer.maskPhone(twilioPhone), LogSanitizer.maskName(appt.getPatient().getName()));

        // Sync cancellation to HubSpot CRM (non-blocking)
        try {
            hubSpotSyncOrchestrator.syncAppointmentStatusChange(appt, "appointment_" + appt.getId());
        } catch (Exception e) {
            log.warn("Failed to sync cancellation to HubSpot, but appointment cancellation succeeded", e);
        }

        return true;
    }

    @Transactional
    public Optional<Appointment> rescheduleAppointment(Long tenantId, String twilioPhone, String doctorKey, String newDate, String newTime) {
        return rescheduleAppointment(tenantId, twilioPhone, null, doctorKey, newDate, newTime);
    }

    @Transactional
    public Optional<Appointment> rescheduleAppointment(
            Long tenantId,
            String twilioPhone,
            String patientName,
            String doctorKey,
            String newDate,
            String newTime
    ) {
        Optional<Appointment> existingOpt = getUpcomingAppointmentEntity(tenantId, twilioPhone, patientName);
        if (!existingOpt.isPresent()) return Optional.empty();

        Appointment existing = existingOpt.get();
        AppointmentSlot oldSlot = existing.getSlot();

        // Tenant-scoped doctor lookup
        Doctor newDoctor =
                doctorRepository
                        .findByKeyIgnoreCaseAndActiveTrueAndTenantId(doctorKey, tenantId)
                        .orElse(null);

        if (newDoctor == null) return Optional.empty();

        LocalDate slotDate;
        try {
            slotDate = LocalDate.parse(newDate);
        } catch (Exception e) {
            return Optional.empty();
        }

        Optional<AppointmentSlot> newSlotOpt =
                slotRepository.findByDoctorIdAndSlotDateAndStartTimeAndStatus(
                        newDoctor.getId(),
                        slotDate,
                        normalizeTime(newTime),
                        AppointmentSlot.Status.AVAILABLE
                );

        if (!newSlotOpt.isPresent()) return Optional.empty();

        AppointmentSlot newSlot = newSlotOpt.get();
        newSlot.setStatus(AppointmentSlot.Status.BOOKED);
        slotRepository.save(newSlot);

        oldSlot.setStatus(AppointmentSlot.Status.AVAILABLE);
        slotRepository.save(oldSlot);

        existing.setDoctor(newDoctor);
        existing.setSlot(newSlot);

        appointmentRepository.save(existing);

        log.info(
                "Rescheduled appointment for {} to {} {} {}",
                LogSanitizer.maskPhone(twilioPhone),
                newDoctor.getName(),
                newDate,
                newTime
        );

        return Optional.of(existing);
    }

    /**
     * Find the nearest upcoming confirmed appointment entity, scoped to a tenant.
     * Only considers slots with slotDate >= today.
     */
    @Transactional(readOnly = true)
    private Optional<Appointment> getUpcomingAppointmentEntity(Long tenantId, String twilioPhone, String patientName) {
        LocalDate today = LocalDate.now();
        List<Appointment> list = appointmentRepository
                .findConfirmedByPhoneAndTenantWithDetails(twilioPhone, tenantId);

        return list.stream()
                .filter(a -> a.getSlot() != null && a.getSlot().getSlotDate() != null
                        && !a.getSlot().getSlotDate().isBefore(today))
                .filter(a -> {
                    if (patientName == null || patientName.isBlank()) return true;
                    String dbName = a.getPatient() != null ? a.getPatient().getName() : null;
                    if (dbName == null || dbName.isBlank()) return false;
                    String dbLower = dbName.trim().toLowerCase();
                    String inLower = patientName.trim().toLowerCase();
                    return dbLower.equals(inLower) || dbLower.contains(inLower) || inLower.contains(dbLower);
                })
                .sorted(Comparator
                        .comparing((Appointment a) -> a.getSlot().getSlotDate())
                        .thenComparing(a -> a.getSlot().getStartTime() != null ? a.getSlot().getStartTime() : ""))
                .findFirst();
    }

    /**
     * Normalizes time to 12-hour format with two-digit hour (e.g. 07:00 PM) to match DB slot startTime.
     */
    private static String normalizeTime(String time) {
        if (time == null) return null;

        String t = time.trim().replace('.', ':');

        if (t.contains(" to ")) {
            t = t.substring(0, t.indexOf(" to ")).trim();
        }

        if (t.matches("\\d{1,2}:\\d{2}\\s*(AM|PM)")) {
            int colon = t.indexOf(':');
            int space = t.indexOf(' ', colon);
            int h = Integer.parseInt(t.substring(0, colon));
            String rest = space > 0 ? t.substring(colon, space).trim() + " " + t.substring(space).trim() : t.substring(colon);
            return String.format("%02d%s", h, rest);
        }

        if (t.matches("\\d{1,2}:\\d{2}")) {
            int h = Integer.parseInt(t.split(":")[0]);
            String m = t.split(":")[1];
            if (h >= 12) {
                return String.format("%02d:%s PM", h == 12 ? 12 : h - 12, m);
            }
            return String.format("%02d:%s AM", h == 0 ? 12 : h, m);
        }

        return t;
    }

    /**
     * Find confirmed appointments for a given date that haven't been reminded yet.
     */
    @Transactional(readOnly = true)
    public List<Appointment> getUnremindedAppointmentsForDate(LocalDate date) {
        return appointmentRepository.findUnremindedForDate(date);
    }

    /**
     * Mark an appointment as reminded so it won't be called again.
     */
    @Transactional
    public void markReminded(Long appointmentId) {
        appointmentRepository.findById(appointmentId).ifPresent(appt -> {
            appt.setReminded(true);
            appointmentRepository.save(appt);
        });
    }
}
