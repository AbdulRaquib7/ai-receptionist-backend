package com.ai.receptionist.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "call_state", indexes = {
    @Index(name = "idx_call_state_call_sid", columnList = "call_sid", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_sid", nullable = false, unique = true)
    private String callSid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ConversationState state = ConversationState.GREETING;

    @Column(name = "selected_doctor_id")
    private Long selectedDoctorId;

    @Column(name = "selected_slot_id")
    private Long selectedSlotId;

    @Column(name = "patient_name")
    private String patientName;

    @Column(name = "patient_phone")
    private String patientPhone;

    @Column(name = "caller_phone")
    private String callerPhone;

    @Column(name = "pending_appointment_id")
    private Long pendingAppointmentId;

    @Column(name = "pending_action")
    private String pendingAction;

    @Column(name = "reschedule_doctor_id")
    private Long rescheduleDoctorId;

    @Column(name = "reschedule_slot_id")
    private Long rescheduleSlotId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
