package com.golias.control_hub.domain.application.entrypoint;

import com.golias.control_hub.domain.application.action.GetApplicationStatusEvents;
import com.golias.control_hub.domain.application.action.GetApplications;
import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import com.golias.control_hub.domain.application.dto.ApplicationStatusEventDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/applications")
public class GetApplicationsController {

    private final GetApplications applications;
    private final GetApplicationStatusEvents statusEvents;

    public GetApplicationsController(GetApplications applications, GetApplicationStatusEvents statusEvents) {
        this.applications = applications;
        this.statusEvents = statusEvents;
    }

    @GetMapping
    public List<ApplicationDTO> getAllApplications() {
        return applications.getRegisteredApplications();
    }

    @GetMapping("/{id}")
    public ApplicationDTO getApplication(@PathVariable UUID id) {
        return applications.getApplication(id);
    }

    /**
     * The application's recorded health transitions, newest first. These are Control Hub's own
     * transitions, not the raw Kubernetes events exposed inline on the application itself.
     */
    @GetMapping("/{id}/events")
    public List<ApplicationStatusEventDTO> getApplicationStatusEvents(@PathVariable UUID id,
            @RequestParam(required = false) Integer limit) {
        return statusEvents.getStatusEvents(id, limit);
    }

}
