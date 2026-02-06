package com.ai.receptionist.service;

import com.ai.receptionist.entity.*;
import com.ai.receptionist.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public List<Doctor> getAllDoctors() {
        return doctorRepository.findByActiveTrue();
    }

    /**
     * Returns available slots for the next 7 days, grouped by doctor and date.
     * Format: { "dr-ahmed": { "2026-02-03": ["09:00 AM", "09:30 AM", ...] }, ... }
     */
    public Map<String, Map<String, List<String>>> getAvailableSlotsForNextWeek() {
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(7);

        List<Doctor> doctors = getAllDoctors();
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();

        for (Doctor d : doctors) {
            List<AppointmentSlot> slots = slotRepository.findByDoctorIdAndSlotDateBetweenAndStatus(
                    d.getId(), today, end, AppointmentSlot.Status.AVAILABLE);
            Map<String, List<String>> byDate = slots.stream()
                    .collect(Collectors.groupingBy(
                            s -> s.getSlotDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                            LinkedHashMap::new,
                            Collectors.mapping(AppointmentSlot::getStartTime, Collectors.toList())
                    ));
            result.put(d.getKey(), byDate);
        }
        return result;
    }

    public Optional<Appointment> getActiveAppointmentByTwilioPhone(String twilioPhone) {
        return appointmentRepository.findFirstByPatientTwilioPhoneAndStatusOrderByCreatedAtDesc(
                twilioPhone, Appointment.Status.CONFIRMED);
    }

    @Transactional
    public Optional<Appointment> bookAppointment(String twilioPhone, String patientName, String patientPhone,
                                                  String doctorKey, String date, String time) {
        Doctor doctor = doctorRepository.findByActiveTrue().stream()
                .filter(d -> d.getKey().equalsIgnoreCase(doctorKey))
                .findFirst().orElse(null);
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

        Optional<AppointmentSlot> slotOpt = slotRepository.findByDoctorIdAndSlotDateAndStartTimeAndStatus(
                doctor.getId(), slotDate, normalizeTime(time), AppointmentSlot.Status.AVAILABLE);
        if (slotOpt.isEmpty()) {
            log.warn("Slot not available: {} {} {}", doctorKey, date, time);
            return Optional.empty();
        }

        Patient patient = patientRepository.findByTwilioPhone(twilioPhone).orElse(null);
        if (patient == null) {
            patient = Patient.builder()
                    .name(patientName != null ? patientName : "Unknown")
                    .phone(patientPhone != null ? patientPhone : twilioPhone)
                    .twilioPhone(twilioPhone)
                    .build();
            patient = patientRepository.save(patient);
        } else {
            if (patientName != null && !patientName.isBlank()) patient.setName(patientName);
            if (patientPhone != null && !patientPhone.isBlank()) patient.setPhone(patientPhone);
            patient = patientRepository.save(patient);
        }

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
        log.info("Booked appointment: {} with {} on {} at {}", patient.getName(), doctor.getName(), date, time);
        return Optional.of(appointment);
    }

    @Transactional
    public boolean cancelAppointment(String twilioPhone) {
        Optional<Appointment> opt = getActiveAppointmentByTwilioPhone(twilioPhone);
        if (opt.isEmpty()) return false;

        Appointment appt = opt.get();
        appt.setStatus(Appointment.Status.CANCELLED);
        appointmentRepository.save(appt);

        AppointmentSlot slot = appt.getSlot();
        slot.setStatus(AppointmentSlot.Status.AVAILABLE);
        slotRepository.save(slot);

        log.info("Cancelled appointment for twilioPhone={}", twilioPhone);
        return true;
    }

    @Transactional
    public Optional<Appointment> rescheduleAppointment(String twilioPhone, String doctorKey, String newDate, String newTime) {
        Optional<Appointment> existingOpt = getActiveAppointmentByTwilioPhone(twilioPhone);
        if (existingOpt.isEmpty()) return Optional.empty();

        Appointment existing = existingOpt.get();
        AppointmentSlot oldSlot = existing.getSlot();

        Doctor newDoctor = doctorRepository.findByActiveTrue().stream()
                .filter(d -> d.getKey().equalsIgnoreCase(doctorKey))
                .findFirst().orElse(null);
        if (newDoctor == null) return Optional.empty();

        LocalDate slotDate;
        try {
            slotDate = LocalDate.parse(newDate);
        } catch (Exception e) {
            return Optional.empty();
        }

        Optional<AppointmentSlot> newSlotOpt = slotRepository.findByDoctorIdAndSlotDateAndStartTimeAndStatus(
                newDoctor.getId(), slotDate, normalizeTime(newTime), AppointmentSlot.Status.AVAILABLE);
        if (newSlotOpt.isEmpty()) return Optional.empty();

        AppointmentSlot newSlot = newSlotOpt.get();
        newSlot.setStatus(AppointmentSlot.Status.BOOKED);
        slotRepository.save(newSlot);

        oldSlot.setStatus(AppointmentSlot.Status.AVAILABLE);
        slotRepository.save(oldSlot);

        existing.setDoctor(newDoctor);
        existing.setSlot(newSlot);
        appointmentRepository.save(existing);

        log.info("Rescheduled appointment for twilioPhone={} to {} {} at {}", twilioPhone, newDoctor.getName(), newDate, newTime);
        return Optional.of(existing);
    }

    private static String normalizeTime(String time) {
        if (time == null) return null;
        String t = time.trim();
        if (t.matches("\\d{1,2}:\\d{2}\\s*(AM|PM)") || t.matches("\\d{1,2}\\.\\d{2}\\s*(AM|PM)")) {
            return t.replace('.', ':');
        }
        if (t.matches("\\d{1,2}:\\d{2}")) {
            int h = Integer.parseInt(t.split(":")[0]);
            String m = t.split(":")[1];
            if (h >= 12) return String.format("%d:%s PM", h == 12 ? 12 : h - 12, m);
            return String.format("%d:%s AM", h == 0 ? 12 : h, m);
        }
        return t;
    }
}
