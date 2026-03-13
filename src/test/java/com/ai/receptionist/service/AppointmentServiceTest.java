package com.ai.receptionist.service;

import com.ai.receptionist.config.ConversationProperties;
import com.ai.receptionist.entity.Appointment;
import com.ai.receptionist.entity.AppointmentSlot;
import com.ai.receptionist.entity.Doctor;
import com.ai.receptionist.entity.Patient;
import com.ai.receptionist.repository.AppointmentRepository;
import com.ai.receptionist.repository.AppointmentSlotRepository;
import com.ai.receptionist.repository.DoctorRepository;
import com.ai.receptionist.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppointmentServiceTest {

    @Mock private DoctorRepository doctorRepository;
    @Mock private AppointmentSlotRepository slotRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private AppointmentRepository appointmentRepository;

    private AppointmentService service;

    private static final Long TENANT_ID = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ConversationProperties props = new ConversationProperties();
        service = new AppointmentService(doctorRepository, slotRepository, patientRepository, appointmentRepository, props, null);
    }

    @Test
    void shouldGetAllActiveDoctorsForTenant() {
        Doctor dr = Doctor.builder().id(1L).name("Dr Smith").active(true).tenantId(TENANT_ID).build();
        when(doctorRepository.findByTenantIdAndActiveTrue(TENANT_ID)).thenReturn(List.of(dr));

        List<Doctor> doctors = service.getAllDoctors(TENANT_ID);
        assertThat(doctors).containsExactly(dr);
        verify(doctorRepository).findByTenantIdAndActiveTrue(TENANT_ID);
    }

    @Test
    void shouldGetAvailableSlotsForTenant() {
        Doctor dr = Doctor.builder().id(1L).key("dr-smith").name("Dr Smith").tenantId(TENANT_ID).build();

        LocalDate today = LocalDate.now();
        AppointmentSlot slot = AppointmentSlot.builder()
                .doctor(dr)
                .slotDate(today)
                .startTime("09:00 AM")
                .status(AppointmentSlot.Status.AVAILABLE)
                .tenantId(TENANT_ID)
                .build();

        when(slotRepository.findAllAvailableSlotsByTenantId(eq(TENANT_ID), any(LocalDate.class), any(LocalDate.class), eq(AppointmentSlot.Status.AVAILABLE)))
                .thenReturn(List.of(slot));

        Map<String, Map<String, List<String>>> result = service.getAvailableSlotsForNextWeek(TENANT_ID);
        assertThat(result).containsKey("dr-smith");
        assertThat(result.get("dr-smith")).containsKey(today.toString());
        assertThat(result.get("dr-smith").get(today.toString())).containsExactly("09:00 AM");
    }

    @Test
    void shouldBookAppointmentWithTenant() {
        Doctor dr = Doctor.builder().id(1L).key("dr-smith").name("Dr Smith").tenantId(TENANT_ID).build();
        when(doctorRepository.findByKeyIgnoreCaseAndActiveTrueAndTenantId("dr-smith", TENANT_ID)).thenReturn(Optional.of(dr));

        LocalDate date = LocalDate.now().plusDays(1);
        AppointmentSlot slot = AppointmentSlot.builder()
                .id(10L).doctor(dr).slotDate(date).startTime("10:00 AM")
                .status(AppointmentSlot.Status.AVAILABLE).tenantId(TENANT_ID).build();

        when(slotRepository.findByDoctorIdAndSlotDateAndStartTimeAndStatus(
                eq(1L), eq(date), eq("10:00 AM"), eq(AppointmentSlot.Status.AVAILABLE)))
                .thenReturn(Optional.of(slot));

        Patient patient = Patient.builder().id(100L).name("John Doe").twilioPhone("+123").tenantId(TENANT_ID).build();
        when(patientRepository.findFirstByTwilioPhoneAndNameIgnoreCaseAndTenantId(anyString(), anyString(), eq(TENANT_ID)))
                .thenReturn(Optional.of(patient));
        when(patientRepository.save(any(Patient.class))).thenReturn(patient);

        Appointment appt = Appointment.builder().id(50L).patient(patient).doctor(dr).slot(slot).tenantId(TENANT_ID).build();
        when(appointmentRepository.save(any(Appointment.class))).thenReturn(appt);

        Optional<Appointment> result = service.bookAppointment(TENANT_ID, "+123", "John Doe", "123456", "dr-smith", date.toString(), "10:00 AM");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(50L);
        assertThat(slot.getStatus()).isEqualTo(AppointmentSlot.Status.BOOKED);
        verify(slotRepository).save(slot);
        verify(appointmentRepository).save(any(Appointment.class));
    }

    @Test
    void shouldReturnEmptyWhenSlotAlreadyBooked() {
        Doctor dr = Doctor.builder().id(1L).key("dr-smith").name("Dr Smith").tenantId(TENANT_ID).build();
        when(doctorRepository.findByKeyIgnoreCaseAndActiveTrueAndTenantId("dr-smith", TENANT_ID)).thenReturn(Optional.of(dr));

        LocalDate date = LocalDate.now().plusDays(1);
        when(slotRepository.findByDoctorIdAndSlotDateAndStartTimeAndStatus(
                anyLong(), any(), anyString(), eq(AppointmentSlot.Status.AVAILABLE)))
                .thenReturn(Optional.empty());

        Optional<Appointment> result = service.bookAppointment(TENANT_ID, "+123", "John", null, "dr-smith", date.toString(), "10:00 AM");

        assertThat(result).isEmpty();
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void shouldCancelAppointmentWithTenant() {
        Doctor dr = Doctor.builder().id(1L).key("dr-smith").name("Dr Smith").tenantId(TENANT_ID).build();
        Patient patient = Patient.builder().id(100L).name("John Doe").twilioPhone("+123").tenantId(TENANT_ID).build();
        LocalDate futureDate = LocalDate.now().plusDays(3);
        AppointmentSlot slot = AppointmentSlot.builder()
                .id(10L).doctor(dr).slotDate(futureDate).startTime("10:00 AM")
                .status(AppointmentSlot.Status.BOOKED).tenantId(TENANT_ID).build();
        Appointment appt = Appointment.builder()
                .id(50L).patient(patient).doctor(dr).slot(slot)
                .status(Appointment.Status.CONFIRMED).tenantId(TENANT_ID).build();

        when(appointmentRepository.findConfirmedByPhoneAndTenantWithDetails("+123", TENANT_ID))
                .thenReturn(List.of(appt));

        boolean cancelled = service.cancelAppointment(TENANT_ID, "+123");

        assertThat(cancelled).isTrue();
        assertThat(appt.getStatus()).isEqualTo(Appointment.Status.CANCELLED);
        assertThat(slot.getStatus()).isEqualTo(AppointmentSlot.Status.AVAILABLE);
        verify(appointmentRepository).save(appt);
        verify(slotRepository).save(slot);
    }

    @Test
    void shouldRescheduleAppointmentWithTenant() {
        Doctor dr = Doctor.builder().id(1L).key("dr-smith").name("Dr Smith").tenantId(TENANT_ID).build();
        Patient patient = Patient.builder().id(100L).name("John Doe").twilioPhone("+123").tenantId(TENANT_ID).build();
        LocalDate oldDate = LocalDate.now().plusDays(3);
        AppointmentSlot oldSlot = AppointmentSlot.builder()
                .id(10L).doctor(dr).slotDate(oldDate).startTime("10:00 AM")
                .status(AppointmentSlot.Status.BOOKED).tenantId(TENANT_ID).build();
        Appointment appt = Appointment.builder()
                .id(50L).patient(patient).doctor(dr).slot(oldSlot)
                .status(Appointment.Status.CONFIRMED).tenantId(TENANT_ID).build();

        when(appointmentRepository.findConfirmedByPhoneAndTenantWithDetails("+123", TENANT_ID))
                .thenReturn(List.of(appt));

        LocalDate newDate = LocalDate.now().plusDays(5);
        AppointmentSlot newSlot = AppointmentSlot.builder()
                .id(20L).doctor(dr).slotDate(newDate).startTime("02:00 PM")
                .status(AppointmentSlot.Status.AVAILABLE).tenantId(TENANT_ID).build();

        when(doctorRepository.findByKeyIgnoreCaseAndActiveTrueAndTenantId("dr-smith", TENANT_ID)).thenReturn(Optional.of(dr));
        when(slotRepository.findByDoctorIdAndSlotDateAndStartTimeAndStatus(
                eq(1L), eq(newDate), eq("02:00 PM"), eq(AppointmentSlot.Status.AVAILABLE)))
                .thenReturn(Optional.of(newSlot));

        Optional<Appointment> result = service.rescheduleAppointment(TENANT_ID, "+123", "dr-smith", newDate.toString(), "02:00 PM");

        assertThat(result).isPresent();
        assertThat(oldSlot.getStatus()).isEqualTo(AppointmentSlot.Status.AVAILABLE);
        assertThat(newSlot.getStatus()).isEqualTo(AppointmentSlot.Status.BOOKED);
        assertThat(appt.getSlot()).isEqualTo(newSlot);
    }

    @Test
    void shouldReturnEmptyWhenNoDoctorFound() {
        when(doctorRepository.findByKeyIgnoreCaseAndActiveTrueAndTenantId("nonexistent", TENANT_ID)).thenReturn(Optional.empty());

        Optional<Appointment> result = service.bookAppointment(TENANT_ID, "+123", "John", null, "nonexistent", "2025-01-15", "10:00 AM");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldMarkAppointmentReminded() {
        Appointment appt = Appointment.builder().id(50L).reminded(false).build();
        when(appointmentRepository.findById(50L)).thenReturn(Optional.of(appt));

        service.markReminded(50L);

        assertThat(appt.isReminded()).isTrue();
        verify(appointmentRepository).save(appt);
    }
}
