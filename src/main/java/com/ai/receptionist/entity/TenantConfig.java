package com.ai.receptionist.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tenant_config", uniqueConstraints =
    @UniqueConstraint(columnNames = {"tenant_id", "config_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "config_key", nullable = false, length = 100)
    private String configKey;

    @Column(name = "config_value", nullable = false, columnDefinition = "TEXT")
    private String configValue;
}
