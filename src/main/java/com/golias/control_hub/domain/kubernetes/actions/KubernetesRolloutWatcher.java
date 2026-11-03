package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.application.action.ApplicationStatusService;
import com.golias.control_hub.domain.application.dto.ApplicationHealth;
import com.golias.control_hub.domain.application.utils.ObservedSpecs;
import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;
import com.golias.control_hub.domain.kubernetes.exceptions.RolloutTimeoutException;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesObservedState;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesRollout;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Confirms a rollout by polling the observed state the informers already maintain, rather than
 * asking the API server again. A patch produces a watch event, so the store converges within
 * milliseconds of the change.
 */
@Service
@Slf4j
public class KubernetesRolloutWatcher implements KubernetesRollout {

    private static final String METRIC = "control_hub.kubernetes.rollout";

    private final KubernetesObservedState observedState;
    private final KubernetesResourceInformers informers;
    private final ApplicationStatusService statusService;
    private final MeterRegistry meterRegistry;
    private final Duration timeout;
    private final Duration pollInterval;

    public KubernetesRolloutWatcher(KubernetesObservedState observedState, KubernetesResourceInformers informers,
            ApplicationStatusService statusService, MeterRegistry meterRegistry,
            @Value("${control.hub.operations.rollout-timeout:180s}") Duration timeout,
            @Value("${control.hub.operations.rollout-poll-interval:1s}") Duration pollInterval) {
        this.observedState = observedState;
        this.informers = informers;
        this.statusService = statusService;
        this.meterRegistry = meterRegistry;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
    }

    @Override
    public void awaitReady(String namespace, String name, long generation) {
        if (!informers.hasSynced()) {
            throw new IllegalStateException("Kubernetes informer caches have not synced, so the rollout of " + namespace
                    + "/" + name + " cannot be confirmed");
        }
        ApplicationKey key = new ApplicationKey(namespace, name);
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "timeout";
        long deadline = System.nanoTime() + timeout.toNanos();

        try {
            while (true) {
                ObservedApplicationState state = observedState.get(key).orElse(null);
                if (converged(state, generation)) {
                    outcome = "ready";
                    log.info("Rollout of {}/{} reached generation {} and every replica is ready", namespace, name,
                            generation);
                    return;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    String reasons = describe(state, generation);
                    log.error("Rollout of {}/{} did not become ready within {}: {}", namespace, name,
                            format(timeout), reasons);
                    throw new RolloutTimeoutException("Rollout of " + namespace + "/" + name
                            + " did not become ready within " + format(timeout) + ": " + reasons);
                }
                Thread.sleep(Duration.ofNanos(Math.min(pollInterval.toNanos(), remaining)));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            throw new RolloutTimeoutException(
                    "Interrupted while confirming the rollout of " + namespace + "/" + name, interrupted);
        } finally {
            Counter.builder(METRIC + ".requests").description("Number of confirmed Kubernetes rollouts")
                    .tag("outcome", outcome).register(meterRegistry).increment();
            sample.stop(Timer.builder(METRIC + ".duration")
                    .description("Time spent waiting for a Kubernetes rollout to become ready").tag("outcome", outcome)
                    .publishPercentileHistogram().register(meterRegistry));
        }
    }

    /**
     * The desired revision is live when the Deployment has observed it and no replica of an older
     * revision is left behind.
     */
    private boolean converged(ObservedApplicationState state, long generation) {
        if (state == null) {
            return false;
        }
        V1Deployment deployment = ObservedSpecs.primary(state.deployment());
        Integer target = ObservedSpecs.replicas(deployment);
        if (target == null) {
            return false;
        }
        var status = deployment.getStatus();
        if (status == null || status.getObservedGeneration() == null || status.getObservedGeneration() < generation) {
            return false;
        }
        return count(status.getUpdatedReplicas()) == target && count(status.getReadyReplicas()) == target
                && count(status.getReplicas()) == target;
    }

    /**
     * A healthy application stuck on an older generation is the misleading case: the patch was
     * accepted but the controller never acted on it, so the generation gap is named explicitly.
     */
    private String describe(ObservedApplicationState state, long generation) {
        ApplicationHealth health = statusService.evaluate(state);
        String reasons = health.reasons().isEmpty() ? "status " + health.status()
                : health.reasons().stream()
                        .map(reason -> reason.code() + " " + reason.resource() + " (" + reason.message() + ")")
                        .collect(Collectors.joining("; "));
        Long observed = observedGeneration(state);
        if (observed != null && observed < generation) {
            return "the Deployment still reports generation " + observed + " while waiting for " + generation + "; "
                    + reasons;
        }
        return reasons;
    }

    private static Long observedGeneration(ObservedApplicationState state) {
        if (state == null) {
            return null;
        }
        V1Deployment deployment = ObservedSpecs.primary(state.deployment());
        return deployment == null || deployment.getStatus() == null ? null
                : deployment.getStatus().getObservedGeneration();
    }

    private static String format(Duration duration) {
        return duration.toSeconds() >= 1 ? duration.toSeconds() + "s" : duration.toMillis() + "ms";
    }

    private static int count(Integer count) {
        return count == null ? 0 : count;
    }
}
