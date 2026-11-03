package com.golias.control_hub.domain.application.dto;

import com.golias.control_hub.domain.application.enums.ApplicationStatus;
import com.golias.control_hub.domain.application.enums.ApplicationStatusReason;

import java.time.ZonedDateTime;

public record ApplicationStatusEventDTO(ApplicationStatus from, ApplicationStatus to,
        ApplicationStatusReason reasonCode, String message, ZonedDateTime occurredAt) {
}
