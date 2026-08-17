package com.golias.control_hub.domain.kubernetes.ports;

import com.golias.control_hub.domain.application.dto.ApplicationResources;
import com.golias.control_hub.domain.kubernetes.dto.DiscoveredApplication;

import java.util.List;

public interface KubernetesDiscovery {

    List<DiscoveredApplication> discoverApplications();

    ApplicationResources hydrateApplication(String namespace, String name);
}
