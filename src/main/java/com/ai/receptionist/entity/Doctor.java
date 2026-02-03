package com.ai.receptionist.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "doctor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String specialization;

    private String description;
    
    private Integer experienceYears;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
