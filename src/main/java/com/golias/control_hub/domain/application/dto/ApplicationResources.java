package com.golias.control_hub.domain.application.dto;

import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record ApplicationResources(
        List<V1Pod> pods,
        V1Deployment deployment,
        List<V1Service> services,
        List<V1Ingress> ingresses,
        List<CoreV1Event> events
) {
}
