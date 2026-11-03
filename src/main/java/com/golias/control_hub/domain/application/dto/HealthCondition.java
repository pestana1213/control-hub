package com.golias.control_hub.domain.application.dto;

import java.time.OffsetDateTime;

public record HealthCondition(String resource, String type, String status, String reason, String message,
        OffsetDateTime lastTransitionTime) {
}
