package com.ai.receptionist.repository;

import com.ai.receptionist.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    Optional<Appointment> findFirstByPatient_TwilioPhoneAndStatusOrderByCreatedAtDesc(
            String twilioPhone, Appointment.Status status);

    List<Appointment> findByPatient_TwilioPhoneAndStatusOrderByCreatedAtDesc(
            String twilioPhone, Appointment.Status status);

    Optional<Appointment> findFirstByPatient_TwilioPhoneAndPatient_NameIgnoreCaseAndStatusOrderByCreatedAtDesc(
            String twilioPhone, String patientName, Appointment.Status status);
}
