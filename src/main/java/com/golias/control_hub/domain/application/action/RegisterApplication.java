package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.kubernetes.dto.DiscoveredApplication;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesDiscovery;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

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
        List<DiscoveredApplication> discoveredApplications = kubernetesDiscovery.discoverApplications();

        //info: Intentionally with bad performance
        for (DiscoveredApplication discoveredApplication : discoveredApplications) {

            Optional<Application> applicationOptional = applicationRepo.
                    findByNameAndNameSpace(discoveredApplication.name(), discoveredApplication.namespace());

            if (applicationOptional.isEmpty()) {
                Application application = Application.builder().name(discoveredApplication.name())
                        .namespace(discoveredApplication.namespace()).build();
                meterRegistry.counter("control_hub.application_register").increment();
                applicationRepo.save(application);
                log.info("New application saved: {}", application);
            }
        }
    }
}