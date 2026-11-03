package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.application.action.ApplicationStatusService;
import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;
import com.golias.control_hub.domain.kubernetes.exceptions.RolloutTimeoutException;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesObservedState;
import com.golias.control_hub.domain.kubernetes.state.InMemoryObservedStateStore;
import io.kubernetes.client.openapi.models.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubernetesRolloutWatcherTests {

    private static final ApplicationKey KEY = new ApplicationKey("production", "api");
    private static final Duration TIMEOUT = Duration.ofMillis(200);
    private static final Duration POLL = Duration.ofMillis(5);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final KubernetesResourceInformers informers = mock(KubernetesResourceInformers.class);

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void returnsOnceTheDesiredRevisionIsFullyReady() {
        var store = new InMemoryObservedStateStore();
        store.put(KEY, state(deployment(2, 2, 2, 2, 3L)));
        assertDoesNotThrow(() -> watcher(store).awaitReady("production", "api", 3L));
    }

    @Test
    void keepsPollingUntilTheNewRevisionIsObserved() {
        var attempts = new AtomicInteger();
        KubernetesObservedState store = store(() -> attempts.incrementAndGet() < 3
                ? state(deployment(2, 2, 2, 2, 1L))
                : state(deployment(2, 2, 2, 2, 2L)));
        assertDoesNotThrow(() -> watcher(store).awaitReady("production", "api", 2L));
        assertEquals(3, attempts.get(), "should have waited for the patched generation to be observed");
    }

    @Test
    void aReadyPreviousRevisionIsNotMistakenForTheNewOne() {
        var store = new InMemoryObservedStateStore();
        store.put(KEY, state(deployment(2, 2, 2, 2, 1L)));
        var message = assertThrows(RolloutTimeoutException.class,
                () -> watcher(store).awaitReady("production", "api", 2L)).getMessage();
        assertTrue(message.contains("did not become ready"), message);
        assertTrue(message.contains("still reports generation 1 while waiting for 2"), message);
    }

    @Test
    void surplusReplicasOfTheOldRevisionBlockConvergence() {
        var store = new InMemoryObservedStateStore();
        store.put(KEY, state(deployment(2, 3, 2, 2, 2L)));
        assertThrows(RolloutTimeoutException.class, () -> watcher(store).awaitReady("production", "api", 2L));
    }

    @Test
    void timeoutExplainsWhyTheRolloutDidNotFinish() {
        var pod = new V1Pod().metadata(new V1ObjectMeta().namespace("production").name("api-7f"))
                .status(new V1PodStatus().containerStatuses(List.of(new V1ContainerStatus().name("api")
                        .state(new V1ContainerState()
                                .waiting(new V1ContainerStateWaiting().reason("CrashLoopBackOff"))))));
        var store = new InMemoryObservedStateStore();
        store.put(KEY, new ObservedApplicationState(List.of(deployment(2, 2, 2, 1, 2L)), List.of(pod), List.of(),
                List.of(), List.of(), Instant.now()));

        var message = assertThrows(RolloutTimeoutException.class,
                () -> watcher(store).awaitReady("production", "api", 2L)).getMessage();

        assertTrue(message.contains("Rollout of production/api did not become ready within 200ms"), message);
        assertTrue(message.contains("REPLICA_NOT_READY"), message);
        assertTrue(message.contains("1 of 2 replicas ready"), message);
        assertTrue(message.contains("POD_CRASH_LOOP"), message);
        assertTrue(message.contains("Pod/production/api-7f/api"), message);
    }

    @Test
    void missingObservedStateTimesOutWithThatReason() {
        var message = assertThrows(RolloutTimeoutException.class,
                () -> watcher(new InMemoryObservedStateStore()).awaitReady("production", "api", 1L)).getMessage();
        assertTrue(message.contains("OBSERVED_STATE_UNAVAILABLE"), message);
    }

    @Test
    void anApplicationScaledToZeroConvergesImmediately() {
        var store = new InMemoryObservedStateStore();
        var deployment = new V1Deployment().metadata(new V1ObjectMeta().namespace("production").name("api"))
                .spec(new V1DeploymentSpec().replicas(0))
                .status(new V1DeploymentStatus().observedGeneration(4L));
        store.put(KEY, state(deployment));
        assertDoesNotThrow(() -> watcher(store).awaitReady("production", "api", 4L));
    }

    @Test
    void unsyncedInformersFailBeforeWaiting() {
        when(informers.hasSynced()).thenReturn(false);
        var watcher = new KubernetesRolloutWatcher(new InMemoryObservedStateStore(), informers,
                new ApplicationStatusService(), registry, TIMEOUT, POLL);
        var exception = assertThrows(IllegalStateException.class, () -> watcher.awaitReady("production", "api", 1L));
        assertTrue(exception.getMessage().contains("have not synced"), exception.getMessage());
    }

    private KubernetesRolloutWatcher watcher(KubernetesObservedState store) {
        when(informers.hasSynced()).thenReturn(true);
        return new KubernetesRolloutWatcher(store, informers, new ApplicationStatusService(), registry, TIMEOUT, POLL);
    }

    /** A store whose answer changes between polls, standing in for the reconciler catching up. */
    private KubernetesObservedState store(java.util.function.Supplier<ObservedApplicationState> answers) {
        return new KubernetesObservedState() {
            @Override
            public Optional<ObservedApplicationState> get(ApplicationKey key) {
                return Optional.of(answers.get());
            }

            @Override
            public void put(ApplicationKey key, ObservedApplicationState state) {
            }

            @Override
            public void remove(ApplicationKey key) {
            }
        };
    }

    private V1Deployment deployment(int desired, int replicas, int updated, int ready, long observedGeneration) {
        return new V1Deployment().metadata(new V1ObjectMeta().namespace("production").name("api"))
                .spec(new V1DeploymentSpec().replicas(desired)).status(new V1DeploymentStatus()
                        .observedGeneration(observedGeneration).replicas(replicas).updatedReplicas(updated)
                        .readyReplicas(ready));
    }

    private ObservedApplicationState state(V1Deployment deployment) {
        return new ObservedApplicationState(List.of(deployment), List.of(), List.of(), List.of(), List.of(),
                Instant.now());
    }
}
