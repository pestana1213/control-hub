package com.golias.control_hub.domain.application.entrypoint;

import com.golias.control_hub.domain.application.action.GetApplications;
import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("/v1/applications")
public class GetApplicationsController {

    private final GetApplications applications;

    public GetApplicationsController(GetApplications applications) {
        this.applications = applications;
    }

    @GetMapping
    public List<ApplicationDTO> getAllApplications() {
        return applications.getRegisteredApplications();
    }
}
