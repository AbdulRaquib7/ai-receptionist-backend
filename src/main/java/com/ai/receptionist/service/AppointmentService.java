package com.ai.receptionist.service;

import com.ai.receptionist.entity.*;
import com.ai.receptionist.repository.*;
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

    // =========================================================
    // DOCTORS
    // =========================================================
    public List<Doctor> getAllDoctors() {
        return doctorRepository.findByActiveTrue();
    }

    // =========================================================
    // AVAILABLE SLOTS (NEXT 7 DAYS)
    // =========================================================
    public Map<String, Map<String, List<String>>> getAvailableSlotsForNextWeek() {

        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(7);

        List<Doctor> doctors = getAllDoctors();
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();

        for (Doctor d : doctors) {

            List<AppointmentSlot> slots =
                    slotRepository.findByDoctorIdAndSlotDateBetweenAndStatus(
                            d.getId(),
                            today,
                            end,
                            AppointmentSlot.Status.AVAILABLE
                    );

            Map<String, List<String>> byDate = slots.stream()
                    .sorted(Comparator.comparing(AppointmentSlot::getStartTime))
                    .collect(Collectors.groupingBy(
                            s -> s.getSlotDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                            LinkedHashMap::new,
                            Collectors.mapping(AppointmentSlot::getStartTime, Collectors.toList())
                    ));

            result.put(d.getKey(), byDate);
        }

        return result;
    }

    // =========================================================
    // EXISTING APPOINTMENT
    // =========================================================
    public Optional<Appointment> getActiveAppointmentByTwilioPhone(String twilioPhone) {
        return appointmentRepository
                .findFirstByPatientTwilioPhoneAndStatusOrderByCreatedAtDesc(
                        twilioPhone,
                        Appointment.Status.CONFIRMED
                );
    }

    @Transactional(readOnly = true)
    public Optional<AppointmentSummary> getActiveAppointmentSummary(String twilioPhone) {

        Optional<Appointment> opt =
                appointmentRepository
                        .findFirstByPatientTwilioPhoneAndStatusOrderByCreatedAtDesc(
                                twilioPhone,
                                Appointment.Status.CONFIRMED
                        );

        if (!opt.isPresent()) return Optional.empty();

        Appointment a = opt.get();

        return Optional.of(
                new AppointmentSummary(
                        a.getDoctor().getName(),
                        a.getSlot().getSlotDate().toString(),
                        a.getSlot().getStartTime()
                )
        );
    }

    public static class AppointmentSummary {
        public final String doctorName;
        public final String slotDate;
        public final String startTime;

        public AppointmentSummary(String doctorName, String slotDate, String startTime) {
            this.doctorName = doctorName;
            this.slotDate = slotDate;
            this.startTime = startTime;
        }
    }

    // =========================================================
    // BOOK APPOINTMENT (CONCURRENCY SAFE)
    // =========================================================
    @Transactional
    public Optional<Appointment> bookAppointment(
            String twilioPhone,
            String patientName,
            String patientPhone,
            String doctorKey,
            String date,
            String time
    ) {

        Doctor doctor =
                doctorRepository
                        .findByKeyIgnoreCaseAndActiveTrue(doctorKey)
                        .orElse(null);

        if (doctor == null) {
            log.warn("Doctor not found: {}", doctorKey);
            return Optional.empty();
        }

        LocalDate slotDate;
        try {
            slotDate = LocalDate.parse(date);
        } catch (Exception e) {
            log.warn("Invalid date: {}", date);
            return Optional.empty();
        }

        // 🔒 PESSIMISTIC LOCK — prevents double booking
        Optional<AppointmentSlot> slotOpt =
                slotRepository.findByDoctorIdAndSlotDateAndStartTimeAndStatus(
                        doctor.getId(),
                        slotDate,
                        normalizeTime(time),
                        AppointmentSlot.Status.AVAILABLE
                );

        if (!slotOpt.isPresent()) {
            log.warn("Slot not available: {} {} {}", doctorKey, date, time);
            return Optional.empty();
        }

        Patient patient =
                patientRepository.findByTwilioPhone(twilioPhone).orElse(null);

        if (patient == null) {
            patient = Patient.builder()
                    .name(patientName != null ? patientName : "Unknown")
                    .phone(patientPhone != null ? patientPhone : twilioPhone)
                    .twilioPhone(twilioPhone)
                    .build();
        }
        // Do NOT overwrite existing patient name/phone - allows multiple appointments
        // with same Twilio number without losing the original patient details

        patient = patientRepository.save(patient);

        AppointmentSlot slot = slotOpt.get();
        slot.setStatus(AppointmentSlot.Status.BOOKED);
        slotRepository.save(slot);

        Appointment appointment = Appointment.builder()
                .patient(patient)
                .doctor(doctor)
                .slot(slot)
                .status(Appointment.Status.CONFIRMED)
                .build();

        appointment = appointmentRepository.save(appointment);

        log.info(
                "Booked appointment: patient={} doctor={} date={} time={}",
                patient.getName(),
                doctor.getName(),
                date,
                time
        );

        return Optional.of(appointment);
    }

    // =========================================================
    // CANCEL
    // =========================================================
    @Transactional
    public boolean cancelAppointment(String twilioPhone) {

        Optional<Appointment> opt = getActiveAppointmentByTwilioPhone(twilioPhone);
        if (!opt.isPresent()) return false;

        Appointment appt = opt.get();
        appt.setStatus(Appointment.Status.CANCELLED);
        appointmentRepository.save(appt);

        AppointmentSlot slot = appt.getSlot();
        slot.setStatus(AppointmentSlot.Status.AVAILABLE);
        slotRepository.save(slot);

        log.info("Cancelled appointment for {}", twilioPhone);
        return true;
    }

    // =========================================================
    // RESCHEDULE
    // =========================================================
    @Transactional
    public Optional<Appointment> rescheduleAppointment(
            String twilioPhone,
            String doctorKey,
            String newDate,
            String newTime
    ) {

        Optional<Appointment> existingOpt = getActiveAppointmentByTwilioPhone(twilioPhone);
        if (!existingOpt.isPresent()) return Optional.empty();

        Appointment existing = existingOpt.get();
        AppointmentSlot oldSlot = existing.getSlot();

        Doctor newDoctor =
                doctorRepository
                        .findByKeyIgnoreCaseAndActiveTrue(doctorKey)
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
                twilioPhone,
                newDoctor.getName(),
                newDate,
                newTime
        );

        return Optional.of(existing);
    }

    // =========================================================
    // TIME NORMALIZATION
    // =========================================================
    private static String normalizeTime(String time) {

        if (time == null) return null;

        String t = time.trim().replace('.', ':');

        if (t.contains(" to ")) {
            t = t.substring(0, t.indexOf(" to ")).trim();
        }

        if (t.matches("\\d{1,2}:\\d{2}\\s*(AM|PM)")) return t;

        if (t.matches("\\d{1,2}:\\d{2}")) {
            int h = Integer.parseInt(t.split(":")[0]);
            String m = t.split(":")[1];
            return (h >= 12)
                    ? String.format("%d:%s PM", h == 12 ? 12 : h - 12, m)
                    : String.format("%d:%s AM", h == 0 ? 12 : h, m);
        }

        return t;
    }
}
