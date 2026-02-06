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

    @Column(nullable = false, unique = true, length = 50)
    private String key;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "schedule_start", nullable = false, length = 5)
    private String scheduleStart;

    @Column(name = "schedule_end", nullable = false, length = 5)
    private String scheduleEnd;

    @Column(nullable = false)
    private boolean active = true;
}
