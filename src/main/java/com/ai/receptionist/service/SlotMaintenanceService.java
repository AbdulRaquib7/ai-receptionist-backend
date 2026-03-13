package com.ai.receptionist.service;

import com.ai.receptionist.entity.AppointmentSlot;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.repository.AppointmentSlotRepository;
import com.ai.receptionist.repository.DoctorRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Daily maintenance job that ensures appointment slots are always available
 * for the next 30 days, and cleans up old available slots.
 */
@Service
@RequiredArgsConstructor
public class SlotMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(SlotMaintenanceService.class);

    private static final int SLOT_HORIZON_DAYS = 30;
    private static final int SLOT_DURATION_MINUTES = 30;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    private final DoctorRepository doctorRepository;
    private final AppointmentSlotRepository slotRepository;

    @Scheduled(cron = "0 0 2 * * *") // Run at 2 AM daily
    @Transactional
    public void regenerateSlots() {
        log.info("Starting daily slot regeneration...");

        LocalDate today = LocalDate.now();
        LocalDate endDate = today.plusDays(SLOT_HORIZON_DAYS);
        int totalCreated = 0;

        List<Doctor> doctors = doctorRepository.findByActiveTrue();
        for (Doctor doctor : doctors) {
            LocalDate lastSlotDate = slotRepository.findMaxSlotDateByDoctorId(doctor.getId());
            LocalDate startDate = (lastSlotDate != null && lastSlotDate.isAfter(today))
                    ? lastSlotDate.plusDays(1) : today;

            if (!startDate.isAfter(endDate)) {
                int created = createSlotsForDoctorInRange(doctor, startDate, endDate);
                totalCreated += created;
            }
        }

        // Clean up old available slots (older than 7 days)
        LocalDate cleanupBefore = today.minusDays(7);
        slotRepository.deleteOldAvailableSlots(cleanupBefore);

        log.info("Slot regeneration complete: {} new slots created, cleaned up slots before {}", totalCreated, cleanupBefore);
    }

    private int createSlotsForDoctorInRange(Doctor doctor, LocalDate startDate, LocalDate endDate) {
        List<AppointmentSlot> newSlots = new ArrayList<>();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            List<String> times = generateTimeSlots(doctor.getScheduleStart(), doctor.getScheduleEnd());
            for (String time : times) {
                if (!slotRepository.existsByDoctorIdAndSlotDateAndStartTime(doctor.getId(), date, time)) {
                    newSlots.add(AppointmentSlot.builder()
                            .doctor(doctor)
                            .slotDate(date)
                            .startTime(time)
                            .status(AppointmentSlot.Status.AVAILABLE)
                            .tenantId(doctor.getTenantId())
                            .build());
                }
            }
        }

        if (!newSlots.isEmpty()) {
            slotRepository.saveAll(newSlots);
            log.info("Created {} slots for {} ({} to {})", newSlots.size(), doctor.getName(), startDate, endDate);
        }
        return newSlots.size();
    }

    /**
     * Generate 30-minute time slots between schedule start and end times.
     * Schedule times are in 24h format (e.g., "09:00", "17:00").
     * Output times are in 12h format (e.g., "09:00 AM", "05:00 PM").
     */
    private List<String> generateTimeSlots(String scheduleStart, String scheduleEnd) {
        List<String> slots = new ArrayList<>();
        LocalTime start = LocalTime.parse(scheduleStart);
        LocalTime end = LocalTime.parse(scheduleEnd);

        LocalTime current = start;
        while (current.isBefore(end)) {
            slots.add(current.format(TIME_FMT));
            current = current.plusMinutes(SLOT_DURATION_MINUTES);
        }
        return slots;
    }
}
