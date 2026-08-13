package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.kubernetes.dto.DiscoveredApplication;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesDiscovery;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class KubernetesApplicationDiscovery implements KubernetesDiscovery {

    private final CoreV1Api coreV1Api;

    private final AppsV1Api appsV1Api;

    public KubernetesApplicationDiscovery(ApiClient apiClient) {
        this.coreV1Api = new CoreV1Api(apiClient);
        this.appsV1Api = new AppsV1Api(apiClient);
    }

    public List<DiscoveredApplication> discoverApplications() {
        List<V1Deployment> deployments = getDeployments();
        log.info("Deployments found: {}", deployments);
        return deployments.stream()
                .map(this::toApplication)
                .filter(Objects::nonNull)
                .toList();
    }

    private DiscoveredApplication toApplication(V1Deployment deployment) {

        V1ObjectMeta metadata = deployment.getMetadata();

        Map<String, String> labels = metadata.getLabels();

        if (labels == null) {
            return null;
        }

        String name = labels.get("app.kubernetes.io/name");

        if (name == null) {
            return null;
        }

        return new DiscoveredApplication(name, metadata.getNamespace(), labels.get("app.kubernetes.io/instance"),
                labels.get("app.kubernetes.io/component")
        );
    }
    public List<V1Pod> getPods(String namespace, String appName) {

        String labelSelector =
                "app.kubernetes.io/name=" + appName;

        try {
            return coreV1Api
                    .listNamespacedPod(namespace).execute().getItems();
        } catch (Exception e) {
            log.error("Failed to retrieve pods for application {} in namespace {}", appName,
                namespace,
                e);

            throw new RuntimeException("Failed to retrieve Kubernetes pods", e);
        }
    }

    private List<V1Deployment> getDeployments() {
        try {
            return appsV1Api.listDeploymentForAllNamespaces().execute().getItems();
        } catch (ApiException e) {
            log.error("Failed to discover Kubernetes deployments", e);
            throw new RuntimeException(e);
        }
    }
}