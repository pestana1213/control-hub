package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.application.utils.Converters;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class GetApplications {

    private final ApplicationRepo applicationRepo;

    private final Converters converters;

    private final MeterRegistry meterRegistry;

    public GetApplications(ApplicationRepo applicationRepo, Converters converters, MeterRegistry meterRegistry) {
        this.applicationRepo = applicationRepo;
        this.converters = converters;
        this.meterRegistry = meterRegistry;
    }

    public List<ApplicationDTO> getRegisteredApplications() {
        try {
            List<Application> applications = applicationRepo.findAll();
            return applications.stream().map(converters::convert).toList();
        }
        catch (Exception e){
            meterRegistry.counter("control-hub.failure", "method", "getRegisteredApplications").increment();
            log.warn("Error getting all applications", e);
            throw e;
        }
    }
}
