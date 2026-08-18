package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.application.dto.ApplicationResources;
import com.golias.control_hub.domain.kubernetes.dto.DiscoveredApplication;
import com.golias.control_hub.domain.kubernetes.exceptions.KubernetesException;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesDiscovery;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

    @Timed(
            value = "control_hub.kubernetes.discovery",
            extraTags = {
                    "resource", "discoverApplications"
            }
    )
    public List<DiscoveredApplication> discoverApplications() {
        List<V1Deployment> deployments = getDeployments();
        return deployments.stream()
                .map(this::toApplication)
                .filter(Objects::nonNull)
                .toList();
    }

    public ApplicationResources hydrateApplication(String namespace, String name) {
        List<V1Pod> pods = getPods(namespace, name);
        V1Deployment deployment = getDeployment(namespace, name);
        List<V1Ingress> ingresses = getIngresses(namespace, name);

        return ApplicationResources.builder()
                .pods(pods)
                .deployment(deployment)
                .ingresses(ingresses)
                .events(getEvents(namespace, deployment, pods, ingresses))
                .build();
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

    @Timed(
            value = "control_hub.kubernetes.discovery",
            extraTags = {
                    "resource", "pods"
            }
    )
    private List<V1Pod> getPods(String namespace, String appName) {

        String labelSelector =
                "app.kubernetes.io/name=" + appName;

        try {
            return coreV1Api
                    .listNamespacedPod(namespace)
                    .labelSelector(labelSelector)
                    .execute().getItems();
        } catch (Exception e) {
            log.error("Failed to retrieve pods for application {} in namespace {}", appName, namespace, e);
            throw new KubernetesException("Failed to retrieve Kubernetes pods", e);
        }
    }

    @Timed(
            value = "control_hub.kubernetes.discovery",
            extraTags = {
                    "resource", "deployment"
            }
    )
    private V1Deployment getDeployment(String namespace, String app) {
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
            throw new KubernetesException("Failed to retrieve Kubernetes deployment", e);
        }
    }

    @Timed(
            value = "control_hub.kubernetes.discovery",
            extraTags = {
                    "resource", "ingress"
            }
    )
    private List<V1Ingress> getIngresses(String namespace, String appName) {
        String labelSelector =
                "app.kubernetes.io/name=" + appName;
        try {
            return networkingV1Api
                    .listNamespacedIngress(namespace)
                    .labelSelector(labelSelector)
                    .execute()
                    .getItems();

        } catch (Exception e) {
            log.error("Failed to retrieve ingresses for application {} in namespace {}", appName, namespace, e);
            throw new KubernetesException("Failed to retrieve Kubernetes ingresses", e);
        }
    }

    private List<V1Deployment> getDeployments() {
        try {
            return appsV1Api.listDeploymentForAllNamespaces().execute().getItems();
        } catch (ApiException e) {
            log.error("Failed to discover Kubernetes deployments", e);
            throw new KubernetesException("Failed to discover Kubernetes deployments" ,e);
        }
    }

    @Timed(
            value = "control_hub.kubernetes.discovery",
            extraTags = {
                    "resource", "events"
            }
    )
    private List<CoreV1Event> getEvents(String namespace, V1Deployment deployment, List<V1Pod> pods,
                                        List<V1Ingress> ingresses) {
        Set<String> resourceNames = pods.stream()
                .map(V1Pod::getMetadata)
                .filter(Objects::nonNull)
                .map(V1ObjectMeta::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        ingresses.stream()
                .map(V1Ingress::getMetadata)
                .filter(Objects::nonNull)
                .map(V1ObjectMeta::getName)
                .filter(Objects::nonNull)
                .forEach(resourceNames::add);

        if (deployment != null && deployment.getMetadata() != null && deployment.getMetadata().getName() != null) {
            resourceNames.add(deployment.getMetadata().getName());
        }

        try {
            return coreV1Api.listNamespacedEvent(namespace).execute().getItems().stream()
                    .filter(event -> event.getInvolvedObject().getName() != null)
                    .filter(event -> resourceNames.contains(
                            event.getInvolvedObject().getName()
                    ))
                    .toList();
        } catch (ApiException e) {
            log.error("Failed to retrieve events in namespace {}", namespace, e);
            throw new KubernetesException("Failed to retrieve Kubernetes events", e);
        }
    }

}
