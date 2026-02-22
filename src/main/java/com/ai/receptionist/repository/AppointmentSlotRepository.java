package com.ai.receptionist.repository;

import com.ai.receptionist.entity.AppointmentSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AppointmentSlotRepository extends JpaRepository<AppointmentSlot, Long> {

    List<AppointmentSlot> findByDoctorIdAndSlotDateBetweenAndStatus(
            Long doctorId, LocalDate from, LocalDate to, AppointmentSlot.Status status);

    Optional<AppointmentSlot> findByDoctorIdAndSlotDateAndStartTimeAndStatus(
            Long doctorId, LocalDate slotDate, String startTime, AppointmentSlot.Status status);
}
