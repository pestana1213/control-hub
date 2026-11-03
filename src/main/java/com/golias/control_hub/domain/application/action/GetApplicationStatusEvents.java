package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.ApplicationStatusEventDTO;
import com.golias.control_hub.domain.application.entities.ApplicationStatusEvent;
import com.golias.control_hub.domain.application.exceptions.ApplicationNotFoundException;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.application.repository.ApplicationStatusEventRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class GetApplicationStatusEvents {

    private final ApplicationRepo applicationRepo;
    private final ApplicationStatusEventRepo eventRepo;
    private final int maxEvents;

    public GetApplicationStatusEvents(ApplicationRepo applicationRepo, ApplicationStatusEventRepo eventRepo,
            @Value("${control.hub.applications.status-events.max-returned:50}") int maxEvents) {
        this.applicationRepo = applicationRepo;
        this.eventRepo = eventRepo;
        this.maxEvents = maxEvents;
    }

    /** Newest first, capped so a caller cannot ask for the whole history. */
    public List<ApplicationStatusEventDTO> getStatusEvents(UUID applicationId, Integer limit) {
        if (!applicationRepo.existsById(applicationId)) {
            throw new ApplicationNotFoundException("Application not registered: " + applicationId);
        }
        int size = limit == null ? maxEvents : Math.clamp(limit, 1, maxEvents);
        return eventRepo.findByApplicationIdOrderByOccurredAtDesc(applicationId, Limit.of(size)).stream()
                .map(GetApplicationStatusEvents::toDTO).toList();
    }

    private static ApplicationStatusEventDTO toDTO(ApplicationStatusEvent event) {
        return new ApplicationStatusEventDTO(event.getFromStatus(), event.getToStatus(), event.getReasonCode(),
                event.getMessage(), event.getOccurredAt());
    }
}
