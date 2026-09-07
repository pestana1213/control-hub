package com.golias.control_hub.domain.kubernetes.dto;

import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;

import java.time.Instant;
import java.util.List;

public record ObservedApplicationState(List<V1Deployment> deployment,
                                       List<V1Pod> pods,
                                       List<V1Service> services,
                                       List<V1Ingress> ingresses,
                                       Instant updatedAt) {
}
