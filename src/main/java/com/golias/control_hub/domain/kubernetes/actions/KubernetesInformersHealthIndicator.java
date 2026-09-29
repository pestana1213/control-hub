package com.golias.control_hub.domain.kubernetes.actions;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("kubernetesInformersHealthIndicator")
public class KubernetesInformersHealthIndicator implements HealthIndicator {

    private final KubernetesResourceInformers informers;

    public KubernetesInformersHealthIndicator(KubernetesResourceInformers informers) {
        this.informers = informers;
    }

    @Override
    public Health health() {
        if (!informers.isRunning()) {
            return Health.down().withDetail("informers", "not running").build();
        }
        if (!informers.hasSynced()) {
            return Health.down().withDetail("informers", "syncing").build();
        }
        return Health.up().withDetail("informers", "synced").build();
    }
}
