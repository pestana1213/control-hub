package com.golias.control_hub.domain.kubernetes.ports;

public interface KubernetesOperations {

    void restart(String namespace, String name);
}
