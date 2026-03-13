package com.ai.receptionist.service.hubspot;

import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.entity.AppointmentSlot;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.entity.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HubSpot sync services
 * These tests verify the sync logic without calling actual HubSpot API
 */
@ExtendWith(MockitoExtension.class)
public class HubSpotSyncServiceTest {

    @Mock
    private HubSpotApiClient apiClient;

    private HubSpotContactSyncService contactSyncService;
    private HubSpotAppointmentSyncService appointmentSyncService;
    private HubSpotDoctorSyncService doctorSyncService;

    @BeforeEach
    void setUp() {
        contactSyncService = new HubSpotContactSyncService(apiClient, null, null);
        appointmentSyncService = new HubSpotAppointmentSyncService(apiClient, contactSyncService, null, null);
        doctorSyncService = new HubSpotDoctorSyncService(apiClient, null);
    }

    @Test
    void testContactDtoConversion() {
        // Create test patient
        Patient patient = Patient.builder()
                .id(1L)
                .name("John Doe")
                .phone("+1234567890")
                .twilioPhone("+0987654321")
                .tenantId(1L)
                .createdAt(Instant.now())
                .build();

        // Convert to DTO
        var contactDto = contactSyncService.convertToContactDto(patient);

        // Assertions
        assertNotNull(contactDto);
        assertEquals("John", contactDto.getFirstName());
        assertEquals("Doe", contactDto.getLastName());
        assertEquals("+1234567890", contactDto.getPhone());
        assertTrue(contactDto.getFullName().contains("John"));
        assertTrue(contactDto.getFullName().contains("Doe"));
    }

    @Test
    void testAppointmentDtoConversion() {
        // Create test data
        Patient patient = Patient.builder()
                .id(1L)
                .name("Jane Smith")
                .phone("+1111111111")
                .tenantId(1L)
                .createdAt(Instant.now())
                .build();

        Doctor doctor = Doctor.builder()
                .id(1L)
                .key("dr-smith")
                .name("Dr. Smith")
                .specialization("Cardiology")
                .scheduleStart("09:00")
                .scheduleEnd("17:00")
                .active(true)
                .tenantId(1L)
                .build();

        AppointmentSlot slot = AppointmentSlot.builder()
                .id(1L)
                .doctor(doctor)
                .slotDate(LocalDate.now().plusDays(1))
                .startTime("10:00 AM")
                .status(AppointmentSlot.Status.BOOKED)
                .tenantId(1L)
                .build();

        Appointment appointment = Appointment.builder()
                .id(1L)
                .patient(patient)
                .doctor(doctor)
                .slot(slot)
                .status(Appointment.Status.CONFIRMED)
                .reminded(false)
                .tenantId(1L)
                .createdAt(Instant.now())
                .build();

        // Convert to DTO
        var appointmentDto = appointmentSyncService.convertToAppointmentDto(appointment);

        // Assertions
        assertNotNull(appointmentDto);
        assertTrue(appointmentDto.getTitle().contains("Jane Smith"));
        assertTrue(appointmentDto.getTitle().contains("Dr. Smith"));
        assertEquals("Dr. Smith", appointmentDto.getDoctorName());
        assertEquals("Cardiology", appointmentDto.getSpecialization());
        assertEquals("Jane Smith", appointmentDto.getPatientName());
        assertEquals("CONFIRMED", appointmentDto.getStatus());
    }

    @Test
    void testSingleNamePatient() {
        Patient patient = Patient.builder()
                .id(2L)
                .name("Madonna")
                .phone("+2222222222")
                .tenantId(1L)
                .createdAt(Instant.now())
                .build();

        var contactDto = contactSyncService.convertToContactDto(patient);

        assertNotNull(contactDto);
        assertEquals("Madonna", contactDto.getFirstName());
        assertEquals("", contactDto.getLastName());
        assertEquals("Madonna", contactDto.getFullName());
    }

    @Test
    void testNullNamePatient() {
        Patient patient = Patient.builder()
                .id(3L)
                .name(null)
                .phone("+3333333333")
                .tenantId(1L)
                .createdAt(Instant.now())
                .build();

        var contactDto = contactSyncService.convertToContactDto(patient);

        assertNotNull(contactDto);
        assertEquals("", contactDto.getFirstName());
        assertEquals("", contactDto.getLastName());
    }
}
