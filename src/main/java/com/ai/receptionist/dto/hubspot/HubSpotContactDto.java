package com.ai.receptionist.dto.hubspot;

import lombok.*;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HubSpotContactDto {
    private String firstName;
    private String lastName;
    private String phone;
    private String email;
    private Map<String, Object> customProperties;

    public String getFullName() {
        String first = firstName != null ? firstName : "";
        String last = lastName != null ? lastName : "";
        return (first + " " + last).trim();
    }
}
