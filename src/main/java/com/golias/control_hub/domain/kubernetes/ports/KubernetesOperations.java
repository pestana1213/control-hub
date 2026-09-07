package com.golias.control_hub.domain.kubernetes.ports;

public interface KubernetesOperations {

    void restart(String namespace, String name);

    void scale(String namespace, String name, int replicas);

    void deploy(String namespace, String name, String image);

    void sync(String namespace, String name);

    void rollback(String namespace, String name, long revision);
}
