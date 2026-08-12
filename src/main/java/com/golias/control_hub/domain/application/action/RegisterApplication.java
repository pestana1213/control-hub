package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
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

    public RegisterApplication(ApplicationRepo applicationRepo, MeterRegistry meterRegistry) {
        this.applicationRepo = applicationRepo;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(cron = "5s")
    public void registerApplication() {
        List<Application> application = applicationRepo.findAll();

        // Check on k8s all the applications, and see if there is any new one
        meterRegistry.counter("control-hub.application_register").increment(2);
    }

}
