package com.ai.receptionist.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "conversation_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_sid", nullable = false, length = 100)
    private String callSid;

    @Column(name = "twilio_phone", length = 30)
    private String twilioPhone;

    @Column(nullable = false, length = 10)
    private String role;  

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
