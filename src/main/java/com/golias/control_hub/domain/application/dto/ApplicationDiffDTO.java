package com.golias.control_hub.domain.application.dto;

import com.golias.control_hub.domain.application.enums.DriftStatus;
import com.golias.control_hub.domain.application.enums.DriftType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApplicationDiffDTO(UUID id, String name, String namespace, DriftStatus status, DesiredSpec desired,
        ObservedSpec observed, List<DriftType> drift, Instant observedAt) {
}
