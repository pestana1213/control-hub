package com.golias.control_hub.domain.application.dto;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
public record ApplicationDTO(UUID id, String name, String namespace, String description, String repositoryUrl,
        List<PodDTO> pods, List<IngressDTO> ingresses, List<EventDTO> events, ApplicationHealth health) {
}
