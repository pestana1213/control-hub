package com.golias.control_hub.domain.kubernetes.ports;

public interface KubernetesOperations {

    /**
     * Patching operations return the Deployment generation the patch produced, so the caller can
     * confirm that exact revision rolled out instead of racing against the previous one.
     */
    long restart(String namespace, String name);

    long scale(String namespace, String name, int replicas);

    long deploy(String namespace, String name, String image);

    void sync(String namespace, String name);

    void rollback(String namespace, String name, long revision);
}
