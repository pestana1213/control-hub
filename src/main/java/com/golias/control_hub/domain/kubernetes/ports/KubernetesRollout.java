package com.golias.control_hub.domain.kubernetes.ports;

public interface KubernetesRollout {

    /**
     * Blocks until the application's Deployment has observed {@code generation} and every replica
     * of that revision is ready.
     *
     * @throws com.golias.control_hub.domain.kubernetes.exceptions.RolloutTimeoutException
     *             when the rollout does not converge in time
     */
    void awaitReady(String namespace, String name, long generation);
}
