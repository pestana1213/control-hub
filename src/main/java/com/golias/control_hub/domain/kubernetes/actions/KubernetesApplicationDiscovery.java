package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.kubernetes.dto.DiscoveredApplication;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesDiscovery;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Ingress;
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

    private final NetworkingV1Api networkingV1Api;

    public KubernetesApplicationDiscovery(ApiClient apiClient) {
        this.coreV1Api = new CoreV1Api(apiClient);
        this.appsV1Api = new AppsV1Api(apiClient);
        this.networkingV1Api = new NetworkingV1Api(apiClient);
    }

    public List<DiscoveredApplication> discoverApplications() {
        List<V1Deployment> deployments = getDeployments();
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
                    .listNamespacedPod(namespace)
                    .labelSelector(labelSelector)
                    .execute().getItems();
        } catch (Exception e) {
            log.error("Failed to retrieve pods for application {} in namespace {}", appName,
                namespace,
                e);

            throw new RuntimeException("Failed to retrieve Kubernetes pods", e);
        }
    }

    public V1Deployment getDeployment(String namespace, String app) {
        String labelSelector = "app.kubernetes.io/name=" + app;

        try {
            List<V1Deployment> deployments = appsV1Api
                    .listNamespacedDeployment(namespace)
                    .labelSelector(labelSelector)
                    .execute()
                    .getItems();

            if (deployments.isEmpty()) {
                log.warn("No deployment found for app {} in namespace {}", app, namespace);
                return null;
            }

            return deployments.getFirst();
        } catch (ApiException e) {
            log.error("Failed to retrieve deployment for application {} in namespace {}", app, namespace, e);
            throw new RuntimeException("Failed to retrieve Kubernetes deployment", e);
        }
    }

    public List<V1Ingress> getIngresses(String namespace, String appName) {
        String labelSelector =
                "app.kubernetes.io/name=" + appName;
        try {
            return networkingV1Api
                    .listNamespacedIngress(namespace)
                    .labelSelector(labelSelector)
                    .execute()
                    .getItems();

        } catch (Exception e) {
            log.error("Failed to retrieve ingresses for application {} in namespace {}",
                    appName, namespace, e);
            throw new RuntimeException("Failed to retrieve Kubernetes ingresses", e);
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