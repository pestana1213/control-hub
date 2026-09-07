package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesObservedState;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

@Service
public class KubernetesReconciler {

    private static final String APPLICATION_LABEL = "app.kubernetes.io/name";

    private final KubernetesResourceInformers informers;
    private final ApplicationRepo applicationRepo;
    private final KubernetesObservedState observedState;

    public KubernetesReconciler(KubernetesResourceInformers informers, ApplicationRepo applicationRepo,
            KubernetesObservedState observedState) {
        this.informers = informers;
        this.applicationRepo = applicationRepo;
        this.observedState = observedState;
    }

    /** Returns empty when the synced caches contain no resources for this application. */
    @Transactional
    public Optional<ObservedApplicationState> reconcile(ApplicationKey key) {
        Objects.requireNonNull(key, "key");
        if (!informers.hasSynced()) {
            throw new IllegalStateException("Kubernetes informer caches have not synced yet");
        }

        List<V1Deployment> deployments = findResources(informers.deployments(), key);
        List<V1Pod> pods = findResources(informers.pods(), key);
        List<V1Service> services = findResources(informers.services(), key);
        List<V1Ingress> ingresses = findResources(informers.ingresses(), key);
        Set<String> resourceNames = new HashSet<>();
        deployments.forEach(resource -> addName(resourceNames, resource));
        pods.forEach(resource -> addName(resourceNames, resource));
        services.forEach(resource -> addName(resourceNames, resource));
        ingresses.forEach(resource -> addName(resourceNames, resource));
        List<CoreV1Event> events = informers.events().getIndexer().list().stream().filter(event -> {
            var metadata = event.getMetadata();
            var involved = event.getInvolvedObject();
            return metadata != null && key.namespace().equals(metadata.getNamespace()) && involved != null
                    && resourceNames.contains(involved.getName());
        }).toList();

        if (deployments.isEmpty() && pods.isEmpty() && services.isEmpty() && ingresses.isEmpty()) {
            observedState.remove(key);
            applicationRepo.findByNameAndNameSpace(key.name(), key.namespace())
                    .filter(application -> !applicationRepo.hasOperationHistory(application.getId()))
                    .ifPresent(applicationRepo::delete);
            return Optional.empty();
        }

        if (applicationRepo.findByNameAndNameSpace(key.name(), key.namespace()).isEmpty()) {
            applicationRepo.save(Application.builder().name(key.name()).namespace(key.namespace()).build());
        }

        ObservedApplicationState state = new ObservedApplicationState(deployments, pods, services, ingresses, events,
                Instant.now());
        observedState.put(key, state);

        return Optional.of(state);
    }

    private void addName(Set<String> names, KubernetesObject resource) {
        if (resource.getMetadata() != null && resource.getMetadata().getName() != null) {
            names.add(resource.getMetadata().getName());
        }
    }

    private <T extends KubernetesObject> List<T> findResources(SharedIndexInformer<T> informer, ApplicationKey key) {
        return informer.getIndexer().list().stream().filter(resource -> {
            var metadata = resource.getMetadata();
            return metadata != null && key.namespace().equals(metadata.getNamespace()) && metadata.getLabels() != null
                    && key.name().equals(metadata.getLabels().get(APPLICATION_LABEL));
        }).toList();
    }
}
