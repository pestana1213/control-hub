package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.*;
import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.exceptions.ApplicationException;
import com.golias.control_hub.domain.application.exceptions.ApplicationNotFoundException;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.application.utils.Converters;
import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesObservedState;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

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

    @Timed(value = "control_hub.kubernetes.discovery", extraTags = { "resource", "getApplication" })
    public ApplicationDTO getApplication(UUID id) {
        Application application = applicationRepo.findById(id)
                .orElseThrow(() -> new ApplicationNotFoundException("Application not registered: " + id));
        return enrichWithK8sInfo(converters.convert(application));
    }

    /**
     * Attaches the observed Kubernetes state. An application whose state cannot be read is reported
     * as UNKNOWN rather than dropped, so the caller always gets a row with a reason attached.
     */
    private ApplicationDTO enrichWithK8sInfo(ApplicationDTO app) {
        try {
            var state = observedState.get(new ApplicationKey(app.namespace(), app.name()))
                    .orElseThrow(() -> new ApplicationException("Observed Kubernetes state is not available"));
            return app.toBuilder().pods(safe(state.pods()).stream().map(converters::toPodDTO).toList())
                    .ingresses(safe(state.ingresses()).stream().map(converters::toIngressDTO).toList())
                    .events(safe(state.events()).stream().map(converters::toEventDTO).toList())
                    .health(statusService.evaluate(state)).build();
        } catch (Exception e) {
            enrichWithK8sInfoFailures.increment();
            log.error("Error fetching k8s info for app {} in namespace {}", app.name(), app.namespace(), e);
            return app.toBuilder().pods(List.of()).ingresses(List.of()).events(List.of())
                    .health(statusService.evaluate(null)).build();
        }
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

}
