package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesObservedState;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubernetesReconcilerTests {

    private final KubernetesResourceInformers informers = mock(KubernetesResourceInformers.class, RETURNS_DEEP_STUBS);
    private final ApplicationRepo applicationRepo = mock(ApplicationRepo.class);
    private final KubernetesObservedState observedState = mock(KubernetesObservedState.class);
    private final KubernetesReconciler reconciler = new KubernetesReconciler(informers, applicationRepo, observedState);
    private final ApplicationKey key = new ApplicationKey("production", "api");

    @BeforeEach
    void setUp() {
        when(informers.hasSynced()).thenReturn(true);
        when(informers.deployments().getIndexer().list()).thenReturn(List.of());
        when(informers.pods().getIndexer().list()).thenReturn(List.of());
        when(informers.services().getIndexer().list()).thenReturn(List.of());
        when(informers.ingresses().getIndexer().list()).thenReturn(List.of());
        when(informers.events().getIndexer().list()).thenReturn(List.of());
    }

    @Test
    void collectsAllResourceTypesAndFiltersByNamespaceAndApplicationLabel() {
        var deployment = new V1Deployment().metadata(metadata("production", "api"));
        var pod = new V1Pod().metadata(metadata("production", "api"));
        var service = new V1Service().metadata(metadata("production", "api"));
        var ingress = new V1Ingress().metadata(metadata("production", "api"));
        when(informers.deployments().getIndexer().list()).thenReturn(List.of(deployment));
        when(informers.pods().getIndexer().list())
                .thenReturn(List.of(pod, new V1Pod().metadata(metadata("staging", "api")),
                        new V1Pod().metadata(metadata("production", "worker")),
                        new V1Pod().metadata(new V1ObjectMeta().namespace("production").name("api")), new V1Pod()));
        when(informers.services().getIndexer().list()).thenReturn(List.of(service));
        when(informers.ingresses().getIndexer().list()).thenReturn(List.of(ingress));

        var state = reconciler.reconcile(key).orElseThrow();

        assertEquals(List.of(deployment), state.deployment());
        assertEquals(List.of(pod), state.pods());
        assertEquals(List.of(service), state.services());
        assertEquals(List.of(ingress), state.ingresses());
        assertNotNull(state.updatedAt());
        verify(applicationRepo).save(argThat(application -> key.name().equals(application.getName())
                && key.namespace().equals(application.getNamespace())));
    }

    @Test
    void returnsEmptyAfterTheLastResourceIsDeletedFromTheCache() {
        when(informers.pods().getIndexer().list())
                .thenReturn(List.of(new V1Pod().metadata(metadata("production", "api"))), List.of());

        assertTrue(reconciler.reconcile(key).isPresent());
        assertTrue(reconciler.reconcile(key).isEmpty());
    }

    @Test
    void rejectsUnsyncedCachesInsteadOfReportingAnAbsentApplication() {
        when(informers.hasSynced()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> reconciler.reconcile(key));
        verifyNoInteractions(applicationRepo);
    }

    @Test
    void deletesRegisteredApplicationWhenAllResourcesAreGone() {
        var application = Application.builder().name(key.name()).namespace(key.namespace()).build();
        when(applicationRepo.findByNameAndNameSpace(key.name(), key.namespace())).thenReturn(Optional.of(application));

        assertTrue(reconciler.reconcile(key).isEmpty());

        verify(applicationRepo).delete(application);
        verify(applicationRepo, never()).save(any());
    }

    @Test
    void preservesExistingApplicationMetadata() {
        var application = Application.builder().name(key.name()).namespace(key.namespace()).description("My API")
                .build();
        when(applicationRepo.findByNameAndNameSpace(key.name(), key.namespace())).thenReturn(Optional.of(application));
        when(informers.pods().getIndexer().list())
                .thenReturn(List.of(new V1Pod().metadata(metadata("production", "api"))));

        assertTrue(reconciler.reconcile(key).isPresent());

        verify(applicationRepo, never()).save(any());
        verify(applicationRepo, never()).delete(any());
    }

    private V1ObjectMeta metadata(String namespace, String application) {
        return new V1ObjectMeta().namespace(namespace).labels(Map.of("app.kubernetes.io/name", application));
    }

    @Test
    void retainsAbsentApplicationWithOperationHistory() {
        var application = Application.builder().id(java.util.UUID.randomUUID()).name(key.name())
                .namespace(key.namespace()).build();
        when(applicationRepo.findByNameAndNameSpace(key.name(), key.namespace())).thenReturn(Optional.of(application));
        when(applicationRepo.hasOperationHistory(application.getId())).thenReturn(true);

        assertTrue(reconciler.reconcile(key).isEmpty());

        verify(applicationRepo, never()).delete(any());
    }
}
