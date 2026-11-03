package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.entities.ApplicationDesiredState;
import com.golias.control_hub.domain.application.enums.DriftStatus;
import com.golias.control_hub.domain.application.enums.DriftType;
import com.golias.control_hub.domain.application.exceptions.ApplicationNotFoundException;
import com.golias.control_hub.domain.application.repository.ApplicationDesiredStateRepo;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;
import com.golias.control_hub.domain.kubernetes.state.InMemoryObservedStateStore;
import io.kubernetes.client.openapi.models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetApplicationDiffTests {

    private static final UUID ID = UUID.randomUUID();
    private static final ApplicationKey KEY = new ApplicationKey("production", "api");
    private static final Instant OBSERVED_AT = Instant.parse("2026-11-03T10:15:30Z");

    private final ApplicationRepo applicationRepo = mock(ApplicationRepo.class);
    private final ApplicationDesiredStateRepo desiredStateRepo = mock(ApplicationDesiredStateRepo.class);
    private final InMemoryObservedStateStore store = new InMemoryObservedStateStore();
    private final GetApplicationDiff service = new GetApplicationDiff(applicationRepo, desiredStateRepo, store);

    @BeforeEach
    void setUp() {
        when(applicationRepo.findById(ID))
                .thenReturn(Optional.of(Application.builder().id(ID).name("api").namespace("production").build()));
        when(desiredStateRepo.findById(ID)).thenReturn(Optional.empty());
    }

    @Test
    void unregisteredApplicationIsNotFound() {
        var unknown = UUID.randomUUID();
        when(applicationRepo.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(ApplicationNotFoundException.class, () -> service.getDiff(unknown));
    }

    @Test
    void anApplicationWithoutADeclaredSpecIsUnmanaged() {
        observe("ghcr.io/api:1.4.1", 1);

        var diff = service.getDiff(ID);

        assertEquals(DriftStatus.UNMANAGED, diff.status());
        assertNull(diff.desired());
        assertEquals("ghcr.io/api:1.4.1", diff.observed().image());
        assertTrue(diff.drift().isEmpty());
    }

    @Test
    void aDeclaredSpecWithoutClusterStateIsUnobservedRatherThanInSync() {
        declare("ghcr.io/api:1.4.2", 2);

        var diff = service.getDiff(ID);

        assertEquals(DriftStatus.UNOBSERVED, diff.status());
        assertNull(diff.observed());
        assertNull(diff.observedAt());
        assertTrue(diff.drift().isEmpty(), "an unobserved application must not be reported as drift-free");
    }

    @Test
    void observedStateWithoutADeploymentIsAlsoUnobserved() {
        declare("ghcr.io/api:1.4.2", 2);
        store.put(KEY, new ObservedApplicationState(List.of(), List.of(), List.of(), List.of(), List.of(),
                OBSERVED_AT));

        assertEquals(DriftStatus.UNOBSERVED, service.getDiff(ID).status());
    }

    @Test
    void matchingSpecsAreInSync() {
        declare("ghcr.io/api:1.4.1", 1);
        observe("ghcr.io/api:1.4.1", 1);

        var diff = service.getDiff(ID);

        assertEquals(DriftStatus.IN_SYNC, diff.status());
        assertTrue(diff.drift().isEmpty());
        assertEquals(OBSERVED_AT, diff.observedAt());
    }

    @Test
    void reportsImageAndReplicaDriftTogether() {
        declare("ghcr.io/api:1.4.2", 2);
        observe("ghcr.io/api:1.4.1", 1);

        var diff = service.getDiff(ID);

        assertEquals(DriftStatus.DRIFTED, diff.status());
        assertEquals(List.of(DriftType.IMAGE, DriftType.REPLICAS), diff.drift());
        assertEquals("ghcr.io/api:1.4.2", diff.desired().image());
        assertEquals(2, diff.desired().replicas());
        assertEquals("ghcr.io/api:1.4.1", diff.observed().image());
        assertEquals(1, diff.observed().replicas());
    }

    @Test
    void reportsOnlyTheFieldThatDiffers() {
        declare("ghcr.io/api:1.4.1", 3);
        observe("ghcr.io/api:1.4.1", 1);

        assertEquals(List.of(DriftType.REPLICAS), service.getDiff(ID).drift());
    }

    @Test
    void anUndeclaredFieldNeverReportsDrift() {
        declare(null, 1);
        observe("ghcr.io/api:9.9.9", 1);

        var diff = service.getDiff(ID);

        assertEquals(DriftStatus.IN_SYNC, diff.status(), "image is not managed, so its value cannot be drift");
        assertTrue(diff.drift().isEmpty());
    }

    @Test
    void aDeploymentWithNoReadableImageDriftsAgainstADeclaredImage() {
        declare("ghcr.io/api:1.4.2", 1);
        var deployment = new V1Deployment().metadata(new V1ObjectMeta().name("api").namespace("production"))
                .spec(new V1DeploymentSpec().replicas(1));
        store.put(KEY, new ObservedApplicationState(List.of(deployment), List.of(), List.of(), List.of(), List.of(),
                OBSERVED_AT));

        var diff = service.getDiff(ID);

        assertEquals(DriftStatus.DRIFTED, diff.status());
        assertEquals(List.of(DriftType.IMAGE), diff.drift());
        assertNull(diff.observed().image());
    }

    private void declare(String image, Integer replicas) {
        when(desiredStateRepo.findById(ID)).thenReturn(Optional.of(
                ApplicationDesiredState.builder().applicationId(ID).image(image).replicas(replicas).build()));
    }

    private void observe(String image, int replicas) {
        var deployment = new V1Deployment().metadata(new V1ObjectMeta().name("api").namespace("production"))
                .spec(new V1DeploymentSpec().replicas(replicas).template(new V1PodTemplateSpec()
                        .spec(new V1PodSpec().containers(List.of(new V1Container().name("api").image(image))))));
        store.put(KEY, new ObservedApplicationState(List.of(deployment), List.of(), List.of(), List.of(), List.of(),
                OBSERVED_AT));
    }
}
