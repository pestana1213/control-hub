package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.enums.ApplicationStatus;
import com.golias.control_hub.domain.application.enums.ApplicationStatusReason;
import com.golias.control_hub.domain.application.enums.PodStatus;
import com.golias.control_hub.domain.application.exceptions.ApplicationNotFoundException;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.application.utils.Converter;
import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;
import com.golias.control_hub.domain.kubernetes.state.InMemoryObservedStateStore;
import io.kubernetes.client.openapi.models.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetApplicationsTests {

    private static final Instant OBSERVED_AT = Instant.parse("2026-11-03T10:15:30Z");

    @Test
    void exposesCentralizedHealthAndPodReadiness() {
        var repo = mock(ApplicationRepo.class);
        var registry = new SimpleMeterRegistry();
        when(repo.findAll()).thenReturn(List.of(Application.builder().name("api").namespace("production").build()));
        var store = new InMemoryObservedStateStore();
        store.put(new ApplicationKey("production", "api"), observedState(1));
        try {
            var app = getApplications(repo, store, registry).getRegisteredApplications().getFirst();
            assertEquals(ApplicationStatus.DEGRADED, app.health().status());
            assertEquals(ApplicationStatusReason.REPLICA_NOT_READY, app.health().reasons().getFirst().code());
            assertEquals(2, app.health().desiredReplicas());
            assertEquals(1, app.health().readyReplicas());
            assertEquals(PodStatus.HEALTHY, app.pods().getFirst().status());
        } finally {
            registry.close();
        }
    }

    @Test
    void reportsTheObservedSpecAndWhenItWasObserved() {
        var repo = mock(ApplicationRepo.class);
        var registry = new SimpleMeterRegistry();
        when(repo.findAll()).thenReturn(List.of(Application.builder().name("api").namespace("production").build()));
        var store = new InMemoryObservedStateStore();
        store.put(new ApplicationKey("production", "api"), observedState(2));
        try {
            var app = getApplications(repo, store, registry).getRegisteredApplications().getFirst();
            assertEquals(ApplicationStatus.HEALTHY, app.health().status());
            assertEquals("ghcr.io/api:1.4.1", app.health().image());
            assertEquals(4, app.health().restartCount());
            assertEquals(OBSERVED_AT, app.health().lastObservedAt(), "the reconciler's observation time, not now()");
        } finally {
            registry.close();
        }
    }

    @Test
    void applicationWithoutObservedStateIsReportedAsUnknownRatherThanDropped() {
        var repo = mock(ApplicationRepo.class);
        var registry = new SimpleMeterRegistry();
        when(repo.findAll()).thenReturn(List.of(Application.builder().name("api").namespace("production").build()));
        try {
            var applications = getApplications(repo, new InMemoryObservedStateStore(), registry)
                    .getRegisteredApplications();
            assertEquals(1, applications.size());
            var app = applications.getFirst();
            assertNotNull(app, "an unobserved application must not be returned as null");
            assertEquals("api", app.name());
            assertEquals(ApplicationStatus.UNKNOWN, app.health().status());
            assertEquals(ApplicationStatusReason.OBSERVED_STATE_UNAVAILABLE, app.health().reasons().getFirst().code());
            assertTrue(app.pods().isEmpty());
        } finally {
            registry.close();
        }
    }

    @Test
    void findsASingleApplicationById() {
        var repo = mock(ApplicationRepo.class);
        var registry = new SimpleMeterRegistry();
        var id = UUID.randomUUID();
        when(repo.findById(id))
                .thenReturn(Optional.of(Application.builder().id(id).name("api").namespace("production").build()));
        var store = new InMemoryObservedStateStore();
        store.put(new ApplicationKey("production", "api"), observedState(2));
        try {
            var app = getApplications(repo, store, registry).getApplication(id);
            assertEquals(id, app.id());
            assertEquals("api", app.name());
            assertEquals(ApplicationStatus.HEALTHY, app.health().status());
        } finally {
            registry.close();
        }
    }

    @Test
    void unregisteredApplicationIdIsNotFound() {
        var repo = mock(ApplicationRepo.class);
        var registry = new SimpleMeterRegistry();
        var id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        try {
            var applications = getApplications(repo, new InMemoryObservedStateStore(), registry);
            assertThrows(ApplicationNotFoundException.class, () -> applications.getApplication(id));
        } finally {
            registry.close();
        }
    }

    private GetApplications getApplications(ApplicationRepo repo, InMemoryObservedStateStore store,
            SimpleMeterRegistry registry) {
        var status = new ApplicationStatusService();
        return new GetApplications(repo, new Converter(status), store, registry, status);
    }

    private ObservedApplicationState observedState(int readyReplicas) {
        var deployment = new V1Deployment().metadata(new V1ObjectMeta().name("api").namespace("production"))
                .spec(new V1DeploymentSpec().replicas(2).template(new V1PodTemplateSpec().spec(new V1PodSpec()
                        .containers(List.of(new V1Container().name("api").image("ghcr.io/api:1.4.1"))))))
                .status(new V1DeploymentStatus().observedGeneration(1L).replicas(2).updatedReplicas(2)
                        .readyReplicas(readyReplicas));
        var pod = new V1Pod().metadata(new V1ObjectMeta().name("api-0").namespace("production"))
                .status(new V1PodStatus().conditions(List.of(new V1PodCondition().type("Ready").status("True")))
                        .containerStatuses(List.of(new V1ContainerStatus().name("api").restartCount(4))));
        return new ObservedApplicationState(List.of(deployment), List.of(pod), List.of(), List.of(), List.of(),
                OBSERVED_AT);
    }
}
