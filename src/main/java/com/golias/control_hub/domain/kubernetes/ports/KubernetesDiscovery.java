package com.golias.control_hub.domain.kubernetes.ports;

import io.kubernetes.client.openapi.models.V1Pod;

import java.util.List;

public interface KubernetesDiscovery {

    List<V1Pod> getPods(String namespace, String application);
}
