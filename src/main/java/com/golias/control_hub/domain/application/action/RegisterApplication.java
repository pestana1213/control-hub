package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesDiscovery;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.models.V1Pod;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class RegisterApplication {

    private final ApplicationRepo applicationRepo;
    private final MeterRegistry meterRegistry;
    private final KubernetesDiscovery kubernetesDiscovery;

    public RegisterApplication(
            ApplicationRepo applicationRepo,
            MeterRegistry meterRegistry,
            KubernetesDiscovery kubernetesDiscovery
    ) {
        this.applicationRepo = applicationRepo;
        this.meterRegistry = meterRegistry;
        this.kubernetesDiscovery = kubernetesDiscovery;
    }

    @Scheduled(fixedDelay = 5000)
    public void registerApplication() {

        List<Application> applications = applicationRepo.findAll();

        log.debug("Checking Kubernetes applications. Currently registered: {}", applications.size());

        meterRegistry.counter("control_hub.application_register").increment();

        kubernetesDiscovery.getPods("", "");
    }
}