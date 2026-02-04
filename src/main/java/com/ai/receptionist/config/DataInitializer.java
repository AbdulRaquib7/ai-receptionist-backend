package com.ai.receptionist.config;

import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.entity.DoctorWorkingHours;
import com.ai.receptionist.repository.DoctorRepository;
import com.ai.receptionist.repository.DoctorWorkingHoursRepository;
import com.ai.receptionist.service.SlotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;

/**
 * Idempotent seeder: inserts doctors and working hours if not present,
 * generates slots for next 7 days. Safe to re-run.
 */
@Component
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final DoctorRepository doctorRepository;
    private final DoctorWorkingHoursRepository workingHoursRepository;
    private final SlotService slotService;

    public DataInitializer(DoctorRepository doctorRepository,
                           DoctorWorkingHoursRepository workingHoursRepository,
                           SlotService slotService) {
        this.doctorRepository = doctorRepository;
        this.workingHoursRepository = workingHoursRepository;
        this.slotService = slotService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void seed() {
        List<Doctor> doctors = doctorRepository.findByActiveTrueOrderByName();
        if (doctors.isEmpty()) {
            log.info("Seeding doctors...");
            doctors = List.of(
                    doctorRepository.save(Doctor.builder().name("Dr. Sarah Johnson").specialization("General Practice").description("Experienced general practitioner").active(true).build()),
                    doctorRepository.save(Doctor.builder().name("Dr. Michael Chen").specialization("Cardiology").description("Heart specialist").active(true).build()),
                    doctorRepository.save(Doctor.builder().name("Dr. Emily Davis").specialization("Pediatrics").description("Children's health").active(true).build())
            );
        }

        for (Doctor d : doctors) {
            if (workingHoursRepository.findByDoctorIdOrderByDayOfWeekAscStartTimeAsc(d.getId()).isEmpty()) {
                for (int day = 1; day <= 6; day++) {
                    workingHoursRepository.save(DoctorWorkingHours.builder().doctor(d).dayOfWeek(day).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(13, 0)).build());
                    workingHoursRepository.save(DoctorWorkingHours.builder().doctor(d).dayOfWeek(day).startTime(LocalTime.of(14, 0)).endTime(LocalTime.of(18, 0)).build());
                }
                log.info("Added working hours for {}", d.getName());
            }
            slotService.getAvailableSlots(d.getId());
        }
        log.info("DataInitializer: doctors={}, slots ready", doctors.size());
    }
}
