package com.ai.receptionist.repository;

import com.ai.receptionist.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    /** Find confirmed appointments for a given date that haven't been reminded yet */
    @Query("SELECT a FROM Appointment a JOIN FETCH a.patient p JOIN FETCH a.doctor d JOIN FETCH a.slot s " +
           "WHERE s.slotDate = :date AND a.status = 'CONFIRMED' AND a.reminded = false")
    List<Appointment> findUnremindedForDate(@Param("date") LocalDate date);

    /** Find confirmed appointments for a tenant & date that haven't been reminded yet */
    @Query("SELECT a FROM Appointment a JOIN FETCH a.patient p JOIN FETCH a.doctor d JOIN FETCH a.slot s " +
           "WHERE a.tenantId = :tenantId AND s.slotDate = :date AND a.status = 'CONFIRMED' AND a.reminded = false")
    List<Appointment> findUnremindedForTenantAndDate(@Param("tenantId") Long tenantId, @Param("date") LocalDate date);

    Optional<Appointment> findFirstByPatient_TwilioPhoneAndStatusOrderByCreatedAtDesc(
            String twilioPhone, Appointment.Status status);

    List<Appointment> findByPatient_TwilioPhoneAndStatusOrderByCreatedAtDesc(
            String twilioPhone, Appointment.Status status);

    Optional<Appointment> findFirstByPatient_TwilioPhoneAndPatient_NameIgnoreCaseAndStatusOrderByCreatedAtDesc(
            String twilioPhone, String patientName, Appointment.Status status);

    /** Eager-fetch patient, doctor, and slot — global (no tenant filter) */
    @Query("SELECT a FROM Appointment a JOIN FETCH a.patient p JOIN FETCH a.doctor d JOIN FETCH a.slot s " +
           "WHERE p.twilioPhone = :phone AND a.status = 'CONFIRMED' ORDER BY a.createdAt DESC")
    List<Appointment> findConfirmedByPhoneWithDetails(@Param("phone") String phone);

    /** Eager-fetch patient, doctor, and slot — scoped to a tenant */
    @Query("SELECT a FROM Appointment a JOIN FETCH a.patient p JOIN FETCH a.doctor d JOIN FETCH a.slot s " +
           "WHERE p.twilioPhone = :phone AND a.tenantId = :tenantId AND a.status = 'CONFIRMED' " +
           "ORDER BY a.createdAt DESC")
    List<Appointment> findConfirmedByPhoneAndTenantWithDetails(
            @Param("phone") String phone,
            @Param("tenantId") Long tenantId);

    /**
     * Find appointments that need a reminder call: appointment time is between now and next hour,
     * reminded = false, and status = CONFIRMED.
     * Uses native SQL to combine slot_date + start_time for the time window filter.
     */
    @Query(value = """
            SELECT a.* FROM appointment a
            JOIN appointment_slot s ON a.slot_id = s.id
            WHERE a.reminded = false
              AND a.status = 'CONFIRMED'
              AND (s.slot_date + (TO_TIMESTAMP(s.start_time, 'HH12:MI AM')::time))::timestamp >= ?1
              AND (s.slot_date + (TO_TIMESTAMP(s.start_time, 'HH12:MI AM')::time))::timestamp <= ?2
            ORDER BY s.slot_date, s.start_time
            """, nativeQuery = true)
    List<Appointment> findAppointmentsToRemind(LocalDateTime now, LocalDateTime nextHour);
}
