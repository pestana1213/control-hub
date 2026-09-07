package com.golias.control_hub.domain.application.dto;

import com.golias.control_hub.domain.application.enums.ApplicationStatusReason;

public record StatusReason(ApplicationStatusReason code, String resource, String message) {
}
