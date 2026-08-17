package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import com.golias.control_hub.domain.application.dto.PodDTO;
import com.golias.control_hub.domain.application.dto.PodStatus;
import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.application.utils.Converters;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesDiscovery;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.golias.control_hub.domain.application.dto.PodStatus.*;

@Service
@Slf4j
public class GetApplications {

    private final ApplicationRepo applicationRepo;

    private final Converters converters;

    private final KubernetesDiscovery kubernetesDiscovery;

    private final MeterRegistry meterRegistry;

    public GetApplications(ApplicationRepo applicationRepo, Converters converters,
                           KubernetesDiscovery kubernetesDiscovery, MeterRegistry meterRegistry) {
        this.applicationRepo = applicationRepo;
        this.converters = converters;
        this.meterRegistry = meterRegistry;
        this.kubernetesDiscovery = kubernetesDiscovery;
    }

    public List<ApplicationDTO> getRegisteredApplications() {
        try {
            List<Application> applications = applicationRepo.findAll();
            return applications.stream()
                    .map(converters::convert)
                    .map(this::enrichWithK8sInfo)
                    .toList();
        } catch (Exception e) {
            meterRegistry.counter("control-hub.failure", "method", "getRegisteredApplications").increment();
            log.warn("Error getting all applications", e);
            throw e;
        }
    }

    private ApplicationDTO enrichWithK8sInfo(ApplicationDTO app) {
        try {
            List<PodDTO> pods = kubernetesDiscovery.getPods(app.namespace(), app.name()).stream()
                    .map(this::toPodDTO)
                    .toList();

            int healthyPods = (int) pods.stream()
                    .filter(p -> HEALTHY.equals(p.status()))
                    .count();

            int desiredPods = getDesiredReplicas(app.namespace(), app.name());

            return new ApplicationDTO(
                    app.name(),
                    app.namespace(),
                    app.description(),
                    app.repositoryUrl(),
                    app.kind(),
                    app.url(),
                    desiredPods,
                    healthyPods,
                    pods
            );
        } catch (Exception e) {
            meterRegistry.counter("control-hub.failure", "method", "enrichWithK8sInfo",
                    "app", app.name()).increment();
            log.warn("Error fetching k8s info for app {} in namespace {}", app.name(), app.namespace(), e);
            return app;
        }
    }

    private int getDesiredReplicas(String namespace, String name) {
        V1Deployment deployment = kubernetesDiscovery.getDeployment(namespace, name);
        if (deployment == null || deployment.getSpec() == null || deployment.getSpec().getReplicas() == null) {
            return 0;
        }
        return deployment.getSpec().getReplicas();
    }

    private PodDTO toPodDTO(V1Pod pod) {
        String name = pod.getMetadata() != null ? pod.getMetadata().getName() : "unknown";

        V1PodStatus podStatus = pod.getStatus();
        String phase = podStatus != null && podStatus.getPhase() != null ? podStatus.getPhase() : "Unknown";

        List<V1ContainerStatus> containerStatuses = podStatus != null ? podStatus.getContainerStatuses() : null;

        int restarts = 0;
        PodStatus status = UNKNOWN;
        if (containerStatuses != null && !containerStatuses.isEmpty()) {
            restarts = containerStatuses.stream()
                    .mapToInt(V1ContainerStatus::getRestartCount)
                    .sum();

            boolean allReady = containerStatuses.stream().allMatch(V1ContainerStatus::getReady);
            status = allReady ? HEALTHY : UNHEALTHY;
        }

        return new PodDTO(name, status, phase, restarts);
    }
}
