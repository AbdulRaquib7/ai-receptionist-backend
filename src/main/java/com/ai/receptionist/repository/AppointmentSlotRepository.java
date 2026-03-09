package com.ai.receptionist.repository;

import com.ai.receptionist.entity.AppointmentSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentSlotRepository extends JpaRepository<AppointmentSlot, Long> {

    List<AppointmentSlot> findByDoctorIdAndSlotDateBetweenAndStatus(
            Long doctorId, LocalDate from, LocalDate to, AppointmentSlot.Status status);

    Optional<AppointmentSlot> findByDoctorIdAndSlotDateAndStartTimeAndStatus(
            Long doctorId, LocalDate slotDate, String startTime, AppointmentSlot.Status status);

    /** All available slots across all tenants — kept for system jobs */
    @Query("SELECT s FROM AppointmentSlot s JOIN FETCH s.doctor d " +
           "WHERE s.slotDate BETWEEN :start AND :end AND s.status = :status AND d.active = true " +
           "ORDER BY d.key, s.slotDate, s.startTime")
    List<AppointmentSlot> findAllAvailableSlots(
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("status") AppointmentSlot.Status status);

    /** Available slots for a specific tenant — used in user-facing call flows */
    @Query("SELECT s FROM AppointmentSlot s JOIN FETCH s.doctor d " +
           "WHERE s.tenantId = :tenantId AND s.slotDate BETWEEN :start AND :end " +
           "AND s.status = :status AND d.active = true " +
           "ORDER BY d.key, s.slotDate, s.startTime")
    List<AppointmentSlot> findAllAvailableSlotsByTenantId(
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("status") AppointmentSlot.Status status);

    /** Find the latest slot date for a given doctor */
    @Query("SELECT MAX(s.slotDate) FROM AppointmentSlot s WHERE s.doctor.id = :doctorId")
    LocalDate findMaxSlotDateByDoctorId(@Param("doctorId") Long doctorId);

    /** Check if a slot already exists for a doctor on a specific date and time */
    boolean existsByDoctorIdAndSlotDateAndStartTime(Long doctorId, LocalDate slotDate, String startTime);

    /** Delete old slots that are past and still available (cleanup) */
    @Modifying
    @Query("DELETE FROM AppointmentSlot s WHERE s.slotDate < :before AND s.status = 'AVAILABLE'")
    void deleteOldAvailableSlots(@Param("before") LocalDate before);
}
