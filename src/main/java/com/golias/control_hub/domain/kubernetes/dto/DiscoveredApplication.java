package com.golias.control_hub.domain.kubernetes.dto;

public record DiscoveredApplication(
        String name,
        String namespace,
        String instance,
        String component
) {}