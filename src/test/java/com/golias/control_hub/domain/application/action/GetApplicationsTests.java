package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.*;
import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.application.utils.Converter;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesDiscovery;
import io.kubernetes.client.openapi.models.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetApplicationsTests {
    @Test
    void exposesCentralizedHealthAndPodReadiness() {
        var repo = mock(ApplicationRepo.class);
        var discovery = mock(KubernetesDiscovery.class);
        var status = new ApplicationStatusService();
        var registry = new SimpleMeterRegistry();
        when(repo.findAll()).thenReturn(List.of(Application.builder().name("api").namespace("production").build()));
        var deployment = new V1Deployment().spec(new V1DeploymentSpec().replicas(2)).status(
                new V1DeploymentStatus().observedGeneration(1L).replicas(2).updatedReplicas(2).readyReplicas(1));
        var pod = new V1Pod()
                .status(new V1PodStatus().conditions(List.of(new V1PodCondition().type("Ready").status("True"))));
        when(discovery.hydrateApplication("production", "api")).thenReturn(ApplicationResources.builder()
                .deployment(deployment).pods(List.of(pod)).ingresses(List.of()).events(List.of()).build());
        try {
            var app = new GetApplications(repo, new Converter(status), discovery, registry, status)
                    .getRegisteredApplications().getFirst();
            assertEquals(ApplicationStatus.DEGRADED, app.health().status());
            assertEquals(ApplicationStatusReason.REPLICA_NOT_READY, app.health().reasons().getFirst().code());
            assertEquals(2, app.desiredPods());
            assertEquals(1, app.healthyPods());
            assertEquals(PodStatus.HEALTHY, app.pods().getFirst().status());
        } finally {
            registry.close();
        }
    }
}
