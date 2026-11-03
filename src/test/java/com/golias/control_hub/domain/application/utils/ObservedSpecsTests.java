package com.golias.control_hub.domain.application.utils;

import io.kubernetes.client.openapi.models.*;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObservedSpecsTests {

    @Test
    void readsImageAndReplicasFromThePrimaryDeployment() {
        var spec = ObservedSpecs.read(List.of(deployment("api", "ghcr.io/api:1.4.2", 2), deployment("worker", "ghcr.io/worker:9", 5)));
        assertEquals("ghcr.io/api:1.4.2", spec.image());
        assertEquals(2, spec.replicas());
    }

    @Test
    void prefersTheContainerNamedAfterTheDeployment() {
        var deployment = deployment("api", "ghcr.io/api:1.4.2", 1);
        deployment.getSpec().getTemplate().getSpec().getContainers()
                .addFirst(new V1Container().name("sidecar").image("ghcr.io/proxy:1"));
        assertEquals("ghcr.io/api:1.4.2", ObservedSpecs.image(deployment));
    }

    @Test
    void fallsBackToTheFirstContainerWhenNoNameMatches() {
        var deployment = deployment("api", "ghcr.io/api:1.4.2", 1);
        deployment.getSpec().getTemplate().getSpec().getContainers().getFirst().setName("app");
        assertEquals("ghcr.io/api:1.4.2", ObservedSpecs.image(deployment));
    }

    @Test
    void missingSpecIsReportedAsNullRatherThanThrowing() {
        assertNull(ObservedSpecs.image(null));
        assertNull(ObservedSpecs.image(new V1Deployment()));
        assertNull(ObservedSpecs.image(new V1Deployment().spec(new V1DeploymentSpec())));
        assertNull(ObservedSpecs.image(new V1Deployment().spec(
                new V1DeploymentSpec().template(new V1PodTemplateSpec().spec(new V1PodSpec().containers(List.of()))))));
        assertNull(ObservedSpecs.replicas(null));
        assertNull(ObservedSpecs.replicas(new V1Deployment()));
        assertNull(ObservedSpecs.primary(null));
        assertNull(ObservedSpecs.primary(List.of()));
        assertNull(ObservedSpecs.primary(Arrays.asList((V1Deployment) null)));
    }

    private V1Deployment deployment(String name, String image, int replicas) {
        var containers = new java.util.ArrayList<V1Container>();
        containers.add(new V1Container().name(name).image(image));
        return new V1Deployment().metadata(new V1ObjectMeta().name(name).namespace("production"))
                .spec(new V1DeploymentSpec().replicas(replicas)
                        .template(new V1PodTemplateSpec().spec(new V1PodSpec().containers(containers))));
    }
}
