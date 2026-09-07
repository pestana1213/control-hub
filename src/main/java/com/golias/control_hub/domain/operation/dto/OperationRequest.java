package com.golias.control_hub.domain.operation.dto;

import com.golias.control_hub.domain.operation.entities.OperationType;

public record OperationRequest(String namespace, String application, OperationType type, Integer replicas, String image,
        Long revision) {
}
