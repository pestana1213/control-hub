package com.golias.control_hub.domain.application.dto;

import com.golias.control_hub.domain.application.enums.PodStatus;

public record PodDTO(String name, PodStatus status, String phase, int restarts) {
}
