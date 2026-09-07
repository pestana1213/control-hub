package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.*;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;
import io.kubernetes.client.openapi.models.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationStatusServiceTests {
    private final ApplicationStatusService service = new ApplicationStatusService();

    @Test
    void healthyWhenDesiredRevisionIsReady() {
        var health = service.evaluate(state(List.of(deployment(2, 2)), List.of()));
        assertEquals(ApplicationStatus.HEALTHY, health.status());
        assertEquals(2, health.desiredReplicas());
        assertEquals(2, health.readyReplicas());
        assertTrue(health.reasons().isEmpty());
    }

    @Test
    void partiallyReadyIsDegraded() {
        var health = service.evaluate(state(List.of(deployment(3, 1)), List.of()));
        assertEquals(ApplicationStatus.DEGRADED, health.status());
        assertEquals(ApplicationStatusReason.REPLICA_NOT_READY, health.reasons().getFirst().code());
    }

    @Test
    void crashLoopWithoutReadyReplicasIsUnhealthy() {
        var health = service.evaluate(state(List.of(deployment(1, 0)), List.of(failingPod("CrashLoopBackOff", false))));
        assertEquals(ApplicationStatus.UNHEALTHY, health.status());
        assertTrue(health.reasons().stream().anyMatch(reason -> reason.code() == ApplicationStatusReason.POD_CRASH_LOOP
                && reason.resource().contains("api/main")));
    }

    @Test
    void initContainerImageFailureIsReportedAlongsideReplicaReason() {
        var health = service.evaluate(state(List.of(deployment(1, 0)), List.of(failingPod("ImagePullBackOff", true))));
        assertEquals(ApplicationStatus.UNHEALTHY, health.status());
        assertTrue(health.reasons().stream()
                .anyMatch(reason -> reason.code() == ApplicationStatusReason.IMAGE_PULL_ERROR));
        assertTrue(health.reasons().stream()
                .anyMatch(reason -> reason.code() == ApplicationStatusReason.REPLICA_NOT_READY));
    }

    @Test
    void rolloutIsDegradedEvenWhenOldReplicasAreReady() {
        var deployment = deployment(2, 2);
        deployment.getStatus().setUpdatedReplicas(1);
        var health = service.evaluate(state(List.of(deployment), List.of()));
        assertEquals(ApplicationStatus.DEGRADED, health.status());
        assertTrue(health.reasons().stream()
                .anyMatch(reason -> reason.code() == ApplicationStatusReason.ROLLOUT_IN_PROGRESS));
    }

    @Test
    void staleGenerationIsARollout() {
        var deployment = deployment(1, 1);
        deployment.getMetadata().setGeneration(2L);
        assertEquals(ApplicationStatus.DEGRADED, service.evaluate(state(List.of(deployment), List.of())).status());
    }

    @Test
    void absentOrIncompleteObservationIsUnknown() {
        assertEquals(ApplicationStatus.UNKNOWN, service.evaluate(null).status());
        assertEquals(ApplicationStatus.UNKNOWN, service.evaluate(state(List.of(), List.of())).status());
        assertEquals(ApplicationStatus.UNKNOWN,
                service.evaluate(state(List.of(new V1Deployment()), List.of())).status());
        var deployment = deployment(1, 0);
        deployment.setStatus(null);
        assertEquals(ApplicationStatus.UNKNOWN, service.evaluate(state(List.of(deployment), List.of())).status());
    }

    @Test
    void intentionallyScaledToZeroIsHealthy() {
        assertEquals(ApplicationStatus.HEALTHY, service.evaluate(state(List.of(deployment(0, 0)), List.of())).status());
    }

    @Test
    void aggregatesMultipleDeployments() {
        var health = service.evaluate(
                state(List.of(deployment(2, 2), deployment(1, 0)), List.of(failingPod("ErrImagePull", false))));
        assertEquals(ApplicationStatus.DEGRADED, health.status());
        assertEquals(3, health.desiredReplicas());
        assertEquals(2, health.readyReplicas());
    }

    @Test
    void podReadinessUsesConditionAndHandlesMissingData() {
        assertEquals(PodStatus.UNKNOWN, service.evaluatePod(new V1Pod()));
        var pod = new V1Pod()
                .status(new V1PodStatus().conditions(List.of(new V1PodCondition().type("Ready").status("True"))));
        assertEquals(PodStatus.HEALTHY, service.evaluatePod(pod));
        pod.getStatus().getConditions().getFirst().setStatus("False");
        assertEquals(PodStatus.UNHEALTHY, service.evaluatePod(pod));
    }

    private V1Deployment deployment(int desired, int ready) {
        return new V1Deployment().metadata(new V1ObjectMeta().name("api").namespace("production").generation(1L))
                .spec(new V1DeploymentSpec().replicas(desired)).status(new V1DeploymentStatus().observedGeneration(1L)
                        .replicas(desired).updatedReplicas(desired).readyReplicas(ready));
    }

    private V1Pod failingPod(String reason, boolean init) {
        var container = new V1ContainerStatus().name("main")
                .state(new V1ContainerState().waiting(new V1ContainerStateWaiting().reason(reason)));
        var status = new V1PodStatus();
        if (init)
            status.setInitContainerStatuses(List.of(container));
        else
            status.setContainerStatuses(List.of(container));
        return new V1Pod().metadata(new V1ObjectMeta().namespace("production").name("api")).status(status);
    }

    private ObservedApplicationState state(List<V1Deployment> deployments, List<V1Pod> pods) {
        return new ObservedApplicationState(deployments, pods, List.of(), List.of(), Instant.now());
    }
}
