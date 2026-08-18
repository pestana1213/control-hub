package com.golias.control_hub.domain.application.dto;

public record PodDTO(String name, PodStatus status, String phase, int restarts) {
}
