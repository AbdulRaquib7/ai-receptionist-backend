package com.ai.receptionist.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "tenant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    @Column(name = "twilio_phone", nullable = false, unique = true, length = 20)
    private String twilioPhone;

    /** Per-tenant Twilio Account SID. NULL → fall back to global env var. */
    @Column(name = "twilio_account_sid", length = 100)
    private String twilioAccountSid;

    /** Per-tenant Twilio Auth Token. NULL → fall back to global env var. */
    @Column(name = "twilio_auth_token", length = 100)
    private String twilioAuthToken;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String timezone = "America/New_York";

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String language = "en";

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** When true, inbound calls route to OpenAI Realtime API pipeline instead of legacy. */
    @Column(name = "use_realtime_api", nullable = false)
    @Builder.Default
    private boolean useRealtimeApi = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
