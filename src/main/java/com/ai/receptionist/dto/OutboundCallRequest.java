package com.ai.receptionist.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class OutboundCallRequest {
    private Long tenantId;
    private String toNumber;
    private Map<String, String> context;
}
