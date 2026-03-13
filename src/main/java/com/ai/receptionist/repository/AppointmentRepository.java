package com.ai.receptionist.repository;

import com.ai.receptionist.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    /** Find confirmed appointments for a given date that haven't been reminded yet */
    @Query("SELECT a FROM Appointment a JOIN FETCH a.patient p JOIN FETCH a.doctor d JOIN FETCH a.slot s " +
           "WHERE s.slotDate = :date AND a.status = 'CONFIRMED' AND a.reminded = false")
    List<Appointment> findUnremindedForDate(@Param("date") LocalDate date);

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

    /** Find all appointments for a tenant (for bulk sync operations) */
    @Query("SELECT a FROM Appointment a JOIN FETCH a.patient p JOIN FETCH a.doctor d JOIN FETCH a.slot s " +
           "WHERE a.tenantId = :tenantId ORDER BY a.createdAt DESC")
    List<Appointment> findByTenantId(@Param("tenantId") Long tenantId);
}
