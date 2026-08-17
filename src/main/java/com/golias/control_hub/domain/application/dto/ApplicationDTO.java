package com.golias.control_hub.domain.application.dto;

import lombok.Builder;

@Builder(toBuilder = true)
public record ApplicationDTO(
        String name,
        String namespace,
        String description,
        String repositoryUrl,
        int desiredPods,
        int healthyPods
) {
}
