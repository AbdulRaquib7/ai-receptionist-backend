package com.ai.receptionist.service;

import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.entity.AppointmentSlot;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.entity.Patient;
import com.ai.receptionist.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Slot generation and concurrency-safe appointment booking.
 * Database is the only source of truth for availability.
 */
@Service
public class SlotService {

    private static final Logger log = LoggerFactory.getLogger(SlotService.class);
    private static final int SLOT_DURATION_MINUTES = 30;
    private static final int DAYS_AHEAD = 7;
    // Default 8h/day: 9-13 and 14-18 (two 4-hour blocks)
    private static final LocalTime BLOCK1_START = LocalTime.of(9, 0);
    private static final LocalTime BLOCK1_END = LocalTime.of(13, 0);
    private static final LocalTime BLOCK2_START = LocalTime.of(14, 0);
    private static final LocalTime BLOCK2_END = LocalTime.of(18, 0);

    private final DoctorRepository doctorRepository;
    private final DoctorWorkingHoursRepository workingHoursRepository;
    private final AppointmentSlotRepository slotRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;

    public SlotService(DoctorRepository doctorRepository,
                       DoctorWorkingHoursRepository workingHoursRepository,
                       AppointmentSlotRepository slotRepository,
                       PatientRepository patientRepository,
                       AppointmentRepository appointmentRepository) {
        this.doctorRepository = doctorRepository;
        this.workingHoursRepository = workingHoursRepository;
        this.slotRepository = slotRepository;
        this.patientRepository = patientRepository;
        this.appointmentRepository = appointmentRepository;
    }

    /**
     * Get available slots for a doctor for the next 7 days.
     * Uses DoctorWorkingHours if configured; otherwise default blocks.
     * Generates slots if none exist.
     */
    @Transactional
    public List<AppointmentSlot> getAvailableSlots(Long doctorId) {

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(DAYS_AHEAD);

        List<AppointmentSlot> any =
            slotRepository.findByDoctorIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                doctorId, from, to);

        if (any.isEmpty()) {
            generateAndSaveSlots(doctorId);
            any = slotRepository.findByDoctorIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    doctorId, from, to);
        }

        return any.stream()
                .filter(s -> s.getStatus() == AppointmentSlot.Status.AVAILABLE)
                .toList();
    }


    @Transactional
    public List<AppointmentSlot> generateAndSaveSlots(Long doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId).orElse(null);
        if (doctor == null) return List.of();

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(DAYS_AHEAD);
        List<AppointmentSlot> slots = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<LocalTime[]> blocks = getBlocksForDay(doctorId, date);
            for (LocalTime[] block : blocks) {
                LocalTime start = block[0];
                LocalTime end = block[1];
                for (LocalTime t = start; t.plus(SLOT_DURATION_MINUTES, ChronoUnit.MINUTES).compareTo(end) <= 0;
                     t = t.plus(SLOT_DURATION_MINUTES, ChronoUnit.MINUTES)) {
                    AppointmentSlot slot = AppointmentSlot.builder()
                            .doctor(doctor)
                            .slotDate(date)
                            .startTime(t)
                            .endTime(t.plus(SLOT_DURATION_MINUTES, ChronoUnit.MINUTES))
                            .status(AppointmentSlot.Status.AVAILABLE)
                            .build();
                    slots.add(slotRepository.save(slot));
                }
            }
        }
        log.info("Generated {} slots for doctor {}", slots.size(), doctorId);
        return slots;
    }

    private List<LocalTime[]> getBlocksForDay(Long doctorId, LocalDate date) {
        int dayOfWeek = date.getDayOfWeek().getValue(); // 1=Mon, 7=Sun
        var wh = workingHoursRepository.findByDoctorIdOrderByDayOfWeekAscStartTimeAsc(doctorId);
        if (!wh.isEmpty()) {
            return wh.stream()
                    .filter(w -> w.getDayOfWeek() == dayOfWeek)
                    .map(w -> new LocalTime[]{w.getStartTime(), w.getEndTime()})
                    .toList();
        }
        // Default: Mon-Sat 9-13, 14-18
        if (dayOfWeek >= 1 && dayOfWeek <= 6) {
            return List.of(
                    new LocalTime[]{BLOCK1_START, BLOCK1_END},
                    new LocalTime[]{BLOCK2_START, BLOCK2_END}
            );
        }
        return List.of();
    }

    /**
     * Attempt to book an appointment. Uses pessimistic lock to prevent double-booking.
     */
    @Transactional
    public BookingResult bookAppointment(Long slotId, String patientName, String patientPhone) {
        AppointmentSlot slot = slotRepository.findByIdForUpdate(slotId);
        if (slot == null) {
            return BookingResult.notFound("Slot not found.");
        }
        if (slot.getStatus() != AppointmentSlot.Status.AVAILABLE) {
            return BookingResult.conflict("This slot is no longer available.");
        }

        Patient patient = patientRepository.findByPhoneNumber(patientPhone)
                .orElse(Patient.builder().name(patientName).phoneNumber(patientPhone).build());
        if (patient.getId() == null) {
            patient = patientRepository.save(patient);
        }

        slot.setStatus(AppointmentSlot.Status.BOOKED);
        slotRepository.save(slot);

        Appointment appointment = Appointment.builder()
                .patient(patient)
                .doctor(slot.getDoctor())
                .slot(slot)
                .build();
        appointmentRepository.save(appointment);

        log.info("Booked appointment: slotId={}, patient={}, doctor={}", slotId, patientName, slot.getDoctor().getName());
        return BookingResult.success(appointment, slot);
    }

    @Transactional(readOnly = true)
    public List<Doctor> getActiveDoctors() {
        return doctorRepository.findByisActiveTrueOrderByName();
    }

    @Transactional(readOnly = true)
    public Optional<Doctor> getDoctor(Long id) {
        return doctorRepository.findById(id);
    }

    public record BookingResult(boolean success, String message, Appointment appointment, AppointmentSlot slot) {
        public static BookingResult success(Appointment a, AppointmentSlot s) {
            return new BookingResult(true, null, a, s);
        }
        public static BookingResult conflict(String msg) {
            return new BookingResult(false, msg, null, null);
        }
        public static BookingResult notFound(String msg) {
            return new BookingResult(false, msg, null, null);
        }
    }
}
