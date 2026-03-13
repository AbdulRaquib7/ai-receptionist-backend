package com.ai.receptionist.dto.hubspot;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HubSpotAppointmentDto {
    private String title;
    private LocalDateTime startDateTime;
    private LocalDateTime endDateTime;
    private String doctorName;
    private String specialization;
    private String patientName;
    private String patientPhone;
    private String status;
    private String notes;
    private Long externalAppointmentId;
    private Long externalDoctorId;
    private String hubspotContactId;
    private String hubspotDoctorId;
}
