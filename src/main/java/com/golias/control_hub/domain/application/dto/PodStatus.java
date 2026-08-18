package com.golias.control_hub.domain.application.dto;

public enum PodStatus {
    HEALTHY("healthy"), UNHEALTHY("unhealthy"), UNKNOWN("unknown");

    PodStatus(String name) {
    }
}
