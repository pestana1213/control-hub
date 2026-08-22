package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.kubernetes.exceptions.KubernetesException;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesOperations;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
public class KubernetesApplicationOperations implements KubernetesOperations {

    private static final String RESTARTED_AT_ANNOTATION = "kubectl.kubernetes.io/restartedAt";

    private final AppsV1Api appsV1Api;

    public KubernetesApplicationOperations(ApiClient apiClient) {
        this.appsV1Api = new AppsV1Api(apiClient);
    }

    @Override
    @Timed(value = "control_hub.kubernetes.operation", extraTags = { "operation", "restart" })
    public void restart(String namespace, String name) {
        String patch = """
                {"spec":{"template":{"metadata":{"annotations":{"%s":"%s"}}}}}
                """.formatted(RESTARTED_AT_ANNOTATION, Instant.now());

        try {
            appsV1Api.patchNamespacedDeployment(name, namespace, new V1Patch(patch)).execute();
            log.info("Restart requested for deployment {} in namespace {}", name, namespace);
        } catch (ApiException e) {
            log.error("Failed to restart deployment {} in namespace {}", name, namespace, e);
            throw new KubernetesException("Failed to restart Kubernetes deployment", e);
        }
    }
}
