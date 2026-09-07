package com.golias.control_hub.domain.application.dto;

import com.golias.control_hub.domain.application.enums.ApplicationStatus;

import java.util.List;

public record ApplicationHealth(ApplicationStatus status, List<StatusReason> reasons, int desiredReplicas,
                                int readyReplicas) {
}
