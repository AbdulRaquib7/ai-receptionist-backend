package com.ai.receptionist.seeder;

import com.ai.receptionist.entity.AppointmentSlot;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.repository.AppointmentSlotRepository;
import com.ai.receptionist.repository.DoctorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    CommandLineRunner seedData(DoctorRepository doctorRepo, AppointmentSlotRepository slotRepo) {
        return args -> {
            if (doctorRepo.count() > 0) {
                log.info("Doctors already seeded, skipping");
                return;
            }

            Doctor drArun = Doctor.builder()
                    .key("dr-arun")
                    .name("Dr Arun")
                    .specialization("General physician")
                    .scheduleStart("09:00")
                    .scheduleEnd("12:00")
                    .active(true)
                    .build();
            
            Doctor drJohn = Doctor.builder()
                    .key("dr-john")
                    .name("Dr John")
                    .specialization("Cardiologist")
                    .scheduleStart("12:00")
                    .scheduleEnd("14:00")
                    .active(true)
                    .build();
            
            Doctor drMurugan = Doctor.builder()
                    .key("dr-murugan")
                    .name("Dr Murugan")
                    .specialization("ENT")
                    .scheduleStart("14:00")
                    .scheduleEnd("18:00")
                    .active(true)
                    .build();

            
            Doctor drAlan = Doctor.builder()
                    .key("dr-alan")
                    .name("Dr Alan")
                    .specialization("Dentist")
                    .scheduleStart("18:00")
                    .scheduleEnd("22:00")
                    .active(true)
                    .build();
            
            drArun = doctorRepo.save(drArun);
            drJohn = doctorRepo.save(drJohn);
            drMurugan = doctorRepo.save(drMurugan);
            drAlan = doctorRepo.save(drAlan);

            String[] arunSlots = {"09:00 AM", "09:30 AM", "10:00 AM", "10:30 AM", "11:00 AM", "11:30 AM"};
            String[] johnSlots = {"12:00 PM", "12:30 PM", "01:00 PM", "01:30 PM"};
            String[] muruganSlots = {"02:00 PM", "02:30 PM", "03:00 PM", "03:30 PM", "04.00PM", "04.30PM", "05.00PM", "05.30PM"};
            String[] alanSlots = {"06:00 PM", "06:30 PM", "07:00 PM", "07:30 PM", "08:00 PM", "08:30 PM", "09:00 PM", "09:30 PM"};

            List<AppointmentSlot> slots = new ArrayList<>();
            LocalDate today = LocalDate.now();
            for (int d = 0; d < 7; d++) {
                LocalDate date = today.plusDays(d);
                for (String t : arunSlots) {
                    slots.add(AppointmentSlot.builder().doctor(drArun).slotDate(date).startTime(t).status(AppointmentSlot.Status.AVAILABLE).build());
                }
                for (String t : johnSlots) {
                    slots.add(AppointmentSlot.builder().doctor(drJohn).slotDate(date).startTime(t).status(AppointmentSlot.Status.AVAILABLE).build());
                }
                for (String t : muruganSlots) {
                    slots.add(AppointmentSlot.builder().doctor(drMurugan).slotDate(date).startTime(t).status(AppointmentSlot.Status.AVAILABLE).build());
                }
                for (String t : alanSlots) {
                    slots.add(AppointmentSlot.builder().doctor(drAlan).slotDate(date).startTime(t).status(AppointmentSlot.Status.AVAILABLE).build());
                }
            }
            slotRepo.saveAll(slots);
            log.info("Seeded 4 doctors and {} slots for 7 days", slots.size());
        };
    }
}
