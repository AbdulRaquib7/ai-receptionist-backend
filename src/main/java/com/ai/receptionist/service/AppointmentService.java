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


    public List<Doctor> getAllDoctors() {
        return doctorRepository.findByActiveTrue();
    }

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

    @Transactional(readOnly = true)
    public List<AppointmentSummary> getActiveAppointmentSummaries(String twilioPhone) {
        List<Appointment> list = appointmentRepository
                .findByPatient_TwilioPhoneAndStatusOrderByCreatedAtDesc(twilioPhone, Appointment.Status.CONFIRMED);
        return list.stream()
                .map(a -> new AppointmentSummary(
                        a.getPatient().getName(),
                        a.getDoctor().getName(),
                        a.getSlot().getSlotDate().toString(),
                        a.getSlot().getStartTime()
                ))
                .collect(Collectors.toList());
    }

    /** Upcoming appointments only: slot date >= today. Use for listing, cancel, reschedule so we never mention past appointments. */
    @Transactional(readOnly = true)
    public List<AppointmentSummary> getUpcomingAppointmentSummaries(String twilioPhone) {
        LocalDate today = LocalDate.now();
        return getActiveAppointmentSummaries(twilioPhone).stream()
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
    public Optional<AppointmentSummary> getActiveAppointmentSummary(String twilioPhone) {
        List<AppointmentSummary> list = getActiveAppointmentSummaries(twilioPhone);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Transactional(readOnly = true)
    public Optional<AppointmentSummary> getActiveAppointmentSummary(String twilioPhone, String patientName) {
        if (patientName == null || patientName.isBlank()) return getActiveAppointmentSummary(twilioPhone);
        Optional<Appointment> opt = appointmentRepository
                .findFirstByPatient_TwilioPhoneAndPatient_NameIgnoreCaseAndStatusOrderByCreatedAtDesc(
                        twilioPhone, patientName.trim(), Appointment.Status.CONFIRMED);
        if (!opt.isPresent()) return Optional.empty();
        Appointment a = opt.get();
        return Optional.of(new AppointmentSummary(
                a.getPatient().getName(),
                a.getDoctor().getName(),
                a.getSlot().getSlotDate().toString(),
                a.getSlot().getStartTime()));
    }

    /** Upcoming appointment by patient name; empty if not found or appointment is in the past. */
    @Transactional(readOnly = true)
    public Optional<AppointmentSummary> getUpcomingAppointmentSummary(String twilioPhone, String patientName) {
        List<AppointmentSummary> upcoming = getUpcomingAppointmentSummaries(twilioPhone);
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
        public final String slotDate;
        public final String startTime;

        public AppointmentSummary(String patientName, String doctorName, String slotDate, String startTime) {
            this.patientName = patientName;
            this.doctorName = doctorName;
            this.slotDate = slotDate;
            this.startTime = startTime;
        }
    }

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
        Patient patient = patientRepository
                .findFirstByTwilioPhoneAndNameIgnoreCase(twilioPhone, name)
                .orElse(null);
        if (patient == null) {
            patient = Patient.builder()
                    .name(name)
                    .phone(StringUtils.hasText(patientPhone) ? patientPhone : twilioPhone)
                    .twilioPhone(twilioPhone)
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
                .build();

        appointment = appointmentRepository.save(appointment);

        log.info(
                "Booked appointment | apptId={} patientId={} name={} phone={} doctor={} date={} time={}",
                appointment.getId(),
                patient.getId(),
                patient.getName(),
                patient.getPhone(),
                doctor.getName(),
                date,
                time
        );

        return Optional.of(appointment);
    }


    @Transactional
    public boolean cancelAppointment(String twilioPhone) {
        return cancelAppointment(twilioPhone, null);
    }

    @Transactional
    public boolean cancelAppointment(String twilioPhone, String patientName) {
        Optional<Appointment> opt = getActiveAppointmentByTwilioPhoneAndPatientName(twilioPhone, patientName);
        if (!opt.isPresent()) return false;

        Appointment appt = opt.get();
        appt.setStatus(Appointment.Status.CANCELLED);
        appointmentRepository.save(appt);

        AppointmentSlot slot = appt.getSlot();
        slot.setStatus(AppointmentSlot.Status.AVAILABLE);
        slotRepository.save(slot);

        log.info("Cancelled appointment for {} ({})", twilioPhone, appt.getPatient().getName());
        return true;
    }

    @Transactional
    public Optional<Appointment> rescheduleAppointment(String twilioPhone, String doctorKey, String newDate, String newTime) {
        return rescheduleAppointment(twilioPhone, null, doctorKey, newDate, newTime);
    }

    @Transactional
    public Optional<Appointment> rescheduleAppointment(
            String twilioPhone,
            String patientName,
            String doctorKey,
            String newDate,
            String newTime
    ) {
        Optional<Appointment> existingOpt = getActiveAppointmentByTwilioPhoneAndPatientName(twilioPhone, patientName);
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
