package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import com.golias.control_hub.domain.application.dto.PodDTO;
import com.golias.control_hub.domain.application.dto.ApplicationResources;
import com.golias.control_hub.domain.application.dto.IngressDTO;
import com.golias.control_hub.domain.application.dto.EventDTO;
import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.exceptions.ApplicationException;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.application.utils.Converters;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesDiscovery;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.micrometer.core.annotation.Timed;
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

    @Timed(
            value = "control_hub.kubernetes.discovery",
            extraTags = {
                    "resource", "getRegisteredApplications"
            }
    )
    public List<ApplicationDTO> getRegisteredApplications() {
        try {
            List<Application> applications = applicationRepo.findAll();
            return applications.stream()
                    .map(converters::convert)
                    .map(this::enrichWithK8sInfo)
                    .toList();
        } catch (Exception e) {
            meterRegistry.counter("control-hub.failure", "method", "getRegisteredApplications").increment();
            log.error("Error getting all applications", e);
            throw new ApplicationException("Failed to get registered applications", e);
        }
    }

    private ApplicationDTO enrichWithK8sInfo(ApplicationDTO app) {
        try {
            ApplicationResources resources = kubernetesDiscovery.hydrateApplication(app.namespace(), app.name());
            List<PodDTO> pods = resources.pods().stream().map(converters::toPodDTO).toList();
            List<IngressDTO> ingressDTOS = resources.ingresses().stream().map(converters::toIngressDTO).toList();
            List<EventDTO> events = resources.events().stream().map(converters::toEventDTO).toList();
            int healthyPods = (int) pods.stream()
                    .filter(p -> HEALTHY.equals(p.status()))
                    .count();

            int desiredPods = getDesiredReplicas(resources.deployment());

            return new ApplicationDTO(
                    app.name(),
                    app.namespace(),
                    app.description(),
                    app.repositoryUrl(),
                    desiredPods,
                    healthyPods,
                    pods,
                    ingressDTOS,
                    events
            );
        } catch (Exception e) {
            meterRegistry.counter("control-hub.failure", "method", "enrichWithK8sInfo",
                    "app", app.name()).increment();
            log.error("Error fetching k8s info for app {} in namespace {}", app.name(), app.namespace(), e);
            throw new ApplicationException("Failed to enrich with kubernetes info", e);
        }
    }

    private int getDesiredReplicas(V1Deployment deployment) {
        if (deployment == null || deployment.getSpec() == null || deployment.getSpec().getReplicas() == null) {
            return 0;
        }
        return deployment.getSpec().getReplicas();
    }

}
