package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.kubernetes.exceptions.KubernetesException;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesOperations;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Slf4j
@Service
public class KubernetesApplicationOperations implements KubernetesOperations {

    private static final String RESTARTED_AT_ANNOTATION = "kubectl.kubernetes.io/restartedAt";
    private static final String RESTART_METRIC = "control_hub.kubernetes.restart";

    private final AppsV1Api appsV1Api;
    private final MeterRegistry meterRegistry;
    private final Counter successfulRestarts;
    private final Counter failedRestarts;
    private final Timer restartDuration;

    public KubernetesApplicationOperations(ApiClient apiClient, MeterRegistry meterRegistry) {
        this.appsV1Api = new AppsV1Api(apiClient);
        this.meterRegistry = meterRegistry;
        this.successfulRestarts = Counter.builder(RESTART_METRIC + ".requests")
                .description("Number of Kubernetes deployment restart requests").tag("outcome", "success")
                .register(meterRegistry);
        this.failedRestarts = Counter.builder(RESTART_METRIC + ".requests")
                .description("Number of Kubernetes deployment restart requests").tag("outcome", "failure")
                .register(meterRegistry);
        this.restartDuration = Timer.builder(RESTART_METRIC + ".duration")
                .description("Time spent requesting a Kubernetes deployment restart").publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Override
    public void restart(String namespace, String name) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            requireNonBlank(namespace, "namespace");
            requireNonBlank(name, "name");

            String patch = """
                    {"spec":{"template":{"metadata":{"annotations":{"%s":"%s"}}}}}
                    """.formatted(RESTARTED_AT_ANNOTATION, Instant.now());

            log.info("Requesting restart for deployment {} in namespace {}", name, namespace);
            appsV1Api.patchNamespacedDeployment(name, namespace, new V1Patch(patch)).execute();
            successfulRestarts.increment();
            log.info("Restart requested for deployment {} in namespace {}", name, namespace);
        } catch (ApiException e) {
            failedRestarts.increment();
            log.error("Failed to restart deployment {} in namespace {} (Kubernetes status: {}, response: {})", name,
                    namespace, e.getCode(), e.getResponseBody(), e);
            throw new KubernetesException("Failed to restart Kubernetes deployment", e);
        } catch (RuntimeException e) {
            failedRestarts.increment();
            log.error("Unexpected failure restarting deployment {} in namespace {}", name, namespace, e);
            throw e;
        } finally {
            sample.stop(restartDuration);
        }
    }

    @Override
    public void scale(String namespace, String name, int replicas) {
        execute("scale", namespace, name,
                () -> patch(namespace, name, "{\"spec\":{\"replicas\":" + replicas + "}}", "scale"));
    }

    @Override
    public void deploy(String namespace, String name, String image) {
        execute("deploy", namespace, name, () -> {
            requireNonBlank(image, "image");
            patch(namespace, name, "{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"" + name
                    + "\",\"image\":\"" + image.replace("\"", "\\\"") + "\"}]}}}}", "deploy");
        });
    }

    @Override
    public void sync(String namespace, String name) {
        execute("sync", namespace, name, () -> {
            throw unsupported("SYNC", namespace, name, "a desired state source is required");
        });
    }

    @Override
    public void rollback(String namespace, String name, long revision) {
        execute("rollback", namespace, name, () -> {
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            throw unsupported("ROLLBACK", namespace, name, "Kubernetes revision history lookup is not implemented yet");
        });
    }

    private void execute(String operation, String namespace, String name, Runnable action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            requireNonBlank(namespace, "namespace");
            requireNonBlank(name, "name");
            action.run();
            Counter.builder("control_hub.kubernetes.operation.requests").tag("operation", operation)
                    .tag("outcome", "success").register(meterRegistry).increment();
        } catch (RuntimeException exception) {
            log.error("Unexpected failure executing {} {} in namespace {}", operation,name, namespace, exception);
            Counter.builder("control_hub.kubernetes.operation.requests").tag("operation", operation)
                    .tag("outcome", "failure").register(meterRegistry).increment();
            throw exception;
        } finally {
            sample.stop(Timer.builder("control_hub.kubernetes.operation.duration").tag("operation", operation)
                    .publishPercentileHistogram().register(meterRegistry));
        }
    }

    private void patch(String namespace, String name, String payload, String operation) {
        try {
            requireNonBlank(namespace, "namespace");
            requireNonBlank(name, "name");
            appsV1Api.patchNamespacedDeployment(name, namespace, new V1Patch(payload)).execute();
        } catch (ApiException e) {
            throw new KubernetesException("Failed to " + operation + " Kubernetes deployment", e);
        }
    }

    private UnsupportedOperationException unsupported(String operation, String namespace, String name,
            String missingInput) {
        return new UnsupportedOperationException(
                operation + " is not executable yet for " + namespace + "/" + name + ": " + missingInput);
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
