package com.golias.control_hub.domain.kubernetes.ports;

import com.golias.control_hub.domain.kubernetes.dto.DiscoveredApplication;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1Pod;

import java.util.List;

public interface KubernetesDiscovery {

    List<V1Pod> getPods(String namespace, String application);

    List<DiscoveredApplication> discoverApplications();

    V1Deployment getDeployment(String namespace, String application);

    List<V1Ingress> getIngresses(String namespace, String appName);
}
