package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.kubernetes.state.ReconciliationQueue;
import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentList;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1IngressList;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

@Slf4j
@Component
public class KubernetesResourceInformers implements SmartLifecycle {

    private static final long RESYNC_MILLIS = Duration.ofMinutes(10).toMillis();

    private final SharedInformerFactory factory;
    private final SharedIndexInformer<V1Deployment> deployments;
    private final SharedIndexInformer<V1Pod> pods;
    private final SharedIndexInformer<V1Service> services;
    private final SharedIndexInformer<V1Ingress> ingresses;
    private final ReconciliationQueue queue;

    private volatile boolean running;

    public KubernetesResourceInformers(ApiClient apiClient, SharedInformerFactory factory, ReconciliationQueue queue) {
        this.factory = factory;
        this.queue = queue;

        var appsApi = new AppsV1Api(apiClient);
        var coreApi = new CoreV1Api(apiClient);
        var networkingApi = new NetworkingV1Api(apiClient);

        deployments = factory.sharedIndexInformerFor(
                params -> appsApi.listDeploymentForAllNamespaces().resourceVersion(params.resourceVersion)
                        .timeoutSeconds(params.timeoutSeconds).watch(params.watch).buildCall(null),
                V1Deployment.class, V1DeploymentList.class, RESYNC_MILLIS);

        pods = factory.sharedIndexInformerFor(
                params -> coreApi.listPodForAllNamespaces().resourceVersion(params.resourceVersion)
                        .timeoutSeconds(params.timeoutSeconds).watch(params.watch).buildCall(null),
                V1Pod.class, V1PodList.class, RESYNC_MILLIS);

        services = factory.sharedIndexInformerFor(
                params -> coreApi.listServiceForAllNamespaces().resourceVersion(params.resourceVersion)
                        .timeoutSeconds(params.timeoutSeconds).watch(params.watch).buildCall(null),
                V1Service.class, V1ServiceList.class, RESYNC_MILLIS);

        ingresses = factory.sharedIndexInformerFor(
                params -> networkingApi.listIngressForAllNamespaces().resourceVersion(params.resourceVersion)
                        .timeoutSeconds(params.timeoutSeconds).watch(params.watch).buildCall(null),
                V1Ingress.class, V1IngressList.class, RESYNC_MILLIS);

        registerHandlers();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        factory.startAllRegisteredInformers();
        running = true;
        log.info("Started Kubernetes informers for deployments, pods, services and ingresses");
    }

    @Override
    @PreDestroy
    public synchronized void stop() {
        if (!running) {
            return;
        }
        factory.stopAllRegisteredInformers();
        running = false;
        log.info("Stopped Kubernetes resource informers");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public boolean hasSynced() {
        return deployments.hasSynced() && pods.hasSynced() && services.hasSynced() && ingresses.hasSynced();
    }

    public SharedIndexInformer<V1Deployment> deployments() {
        return deployments;
    }

    public SharedIndexInformer<V1Pod> pods() {
        return pods;
    }

    public SharedIndexInformer<V1Service> services() {
        return services;
    }

    public SharedIndexInformer<V1Ingress> ingresses() {
        return ingresses;
    }

    private void registerHandlers() {
        deployments.addEventHandler(handler("Deployment"));
        pods.addEventHandler(handler("Pod"));
        services.addEventHandler(handler("Service"));
        ingresses.addEventHandler(handler("Ingress"));
    }

    private <T extends KubernetesObject> ResourceEventHandler<T> handler(String resourceType) {
        return new ResourceEventHandler<>() {
            @Override
            public void onAdd(T resource) {
                log.debug("Observed Kubernetes {} add: {}", resourceType, resourceName(resource));
                enqueue(applicationKey(resource));
            }

            @Override
            public void onUpdate(T oldResource, T newResource) {
                String oldVersion = resourceVersion(oldResource);
                String newVersion = resourceVersion(newResource);
                if (!Objects.equals(oldVersion, newVersion)) {
                    log.debug("Observed Kubernetes {} update: {}", resourceType, resourceName(newResource));
                }
                ApplicationKey oldKey = applicationKey(oldResource);
                ApplicationKey newKey = applicationKey(newResource);
                if (!Objects.equals(oldKey, newKey)) {
                    enqueue(oldKey);
                }
                // Include informer resyncs so a previously missed event can be retried.
                enqueue(newKey);
            }

            @Override
            public void onDelete(T resource, boolean deletedFinalStateUnknown) {
                log.debug("Observed Kubernetes {} delete: {}", resourceType, resourceName(resource));
                enqueue(applicationKey(resource));
            }
        };
    }

    private ApplicationKey applicationKey(KubernetesObject resource) {
        var metadata = resource.getMetadata();
        if (metadata == null || metadata.getLabels() == null) {
            return null;
        }
        String namespace = metadata.getNamespace();
        String name = metadata.getLabels().get("app.kubernetes.io/name");
        if (namespace == null || namespace.isBlank() || name == null || name.isBlank()) {
            return null;
        }
        return new ApplicationKey(namespace, name);
    }

    private void enqueue(ApplicationKey key) {
        if (key != null) {
            queue.enqueue(key);
        }
    }

    private String resourceName(KubernetesObject resource) {
        var metadata = resource.getMetadata();
        if (metadata == null) {
            return "unknown";
        }
        return metadata.getNamespace() + "/" + metadata.getName();
    }

    private String resourceVersion(KubernetesObject resource) {
        return resource.getMetadata() == null ? null : resource.getMetadata().getResourceVersion();
    }
}
