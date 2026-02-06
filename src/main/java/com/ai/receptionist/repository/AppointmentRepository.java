package com.ai.receptionist.repository;

import com.ai.receptionist.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    Optional<Appointment> findFirstByPatientTwilioPhoneAndStatusOrderByCreatedAtDesc(
            String twilioPhone, Appointment.Status status);
}
