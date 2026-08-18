package com.golias.control_hub.domain.application.dto;

import java.time.OffsetDateTime;

public record EventDTO(
        String type,
        String reason,
        String message,
        Integer count,
        OffsetDateTime firstTimestamp,
        OffsetDateTime lastTimestamp,
        String involvedObjectKind,
        String involvedObjectName,
        String sourceComponent,
        String sourceHost
) {
}
