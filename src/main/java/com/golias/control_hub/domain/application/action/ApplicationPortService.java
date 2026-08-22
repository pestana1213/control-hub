package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.exceptions.ApplicationException;
import com.golias.control_hub.domain.application.port.ApplicationPort;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
public class ApplicationPortService implements ApplicationPort {

    private final ApplicationRepo applicationRepo;

    public ApplicationPortService(ApplicationRepo applicationRepo) {
        this.applicationRepo = applicationRepo;
    }

    @Override
    public Application findApplication(String namespace, String name) {
        return applicationRepo.findByNameAndNameSpace(name, namespace)
                .orElseThrow(() -> new ApplicationException("Application not registered"));
    }
}
