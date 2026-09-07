package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.*;
import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.exceptions.ApplicationException;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.application.utils.Converters;
import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesObservedState;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;
import java.time.Instant;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class GetApplications {

    private final ApplicationStatusService statusService;
    private final ApplicationRepo applicationRepo;

    private final Converters converters;

    private final KubernetesObservedState observedState;

    private final Counter getRegisteredApplicationsFailures;

    private final Counter enrichWithK8sInfoFailures;

    public GetApplications(ApplicationRepo applicationRepo, Converters converters,
            KubernetesObservedState observedState, MeterRegistry meterRegistry,
            ApplicationStatusService statusService) {
        this.statusService = statusService;
        this.applicationRepo = applicationRepo;
        this.converters = converters;
        this.observedState = observedState;
        this.getRegisteredApplicationsFailures = meterRegistry.counter("control_hub.failure", "method",
                "getRegisteredApplications");
        this.enrichWithK8sInfoFailures = meterRegistry.counter("control_hub.failure", "method", "enrichWithK8sInfo");
    }

    @Timed(value = "control_hub.kubernetes.discovery", extraTags = { "resource", "getRegisteredApplications" })
    public List<ApplicationDTO> getRegisteredApplications() {
        try {
            List<Application> applications = applicationRepo.findAll();
            return applications.stream().map(converters::convert).map(this::enrichWithK8sInfo).toList();
        } catch (Exception e) {
            getRegisteredApplicationsFailures.increment();
            log.error("Error getting all applications", e);
            throw new ApplicationException("Failed to get registered applications", e);
        }
    }

    private ApplicationDTO enrichWithK8sInfo(ApplicationDTO app) {
        try {
            var state = observedState.get(new ApplicationKey(app.namespace(), app.name()))
                    .orElseThrow(() -> new ApplicationException("Observed Kubernetes state is not available"));
            ApplicationResources resources = ApplicationResources.builder()
                    .deployment(state.deployment().isEmpty() ? null : state.deployment().getFirst()).pods(state.pods())
                    .services(state.services()).ingresses(state.ingresses()).events(state.events()).build();
            List<PodDTO> pods = resources.pods().stream().map(converters::toPodDTO).toList();
            List<IngressDTO> ingressDTOS = resources.ingresses().stream().map(converters::toIngressDTO).toList();
            List<EventDTO> events = resources.events().stream().map(converters::toEventDTO).toList();
            ApplicationHealth health = statusService.evaluate(new ObservedApplicationState(
                    resources.deployment() == null ? List.of() : List.of(resources.deployment()), resources.pods(),
                    resources.services(), resources.ingresses(), resources.events(), Instant.now()));

            return new ApplicationDTO(app.name(), app.namespace(), app.description(), app.repositoryUrl(),
                    health.desiredReplicas(), health.readyReplicas(), pods, ingressDTOS, events, health);
        } catch (Exception e) {
            enrichWithK8sInfoFailures.increment();
            log.error("Error fetching k8s info for app {} in namespace {}", app.name(), app.namespace(), e);
            throw new ApplicationException("Failed to enrich with kubernetes info", e);
        }
    }

}
