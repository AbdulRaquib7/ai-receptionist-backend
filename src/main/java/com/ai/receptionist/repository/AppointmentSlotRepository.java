package com.ai.receptionist.repository;

import com.ai.receptionist.entity.AppointmentSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface AppointmentSlotRepository extends JpaRepository<AppointmentSlot, Long> {

    List<AppointmentSlot> findByDoctorIdAndSlotDateBetweenAndStatusOrderBySlotDateAscStartTimeAsc(
            Long doctorId,
            LocalDate from,
            LocalDate to,
            AppointmentSlot.Status status
    );

    List<AppointmentSlot> findByDoctorIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
            Long doctorId,
            LocalDate from,
            LocalDate to
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AppointmentSlot s WHERE s.id = :id")
    AppointmentSlot findByIdForUpdate(@Param("id") Long id);
}

