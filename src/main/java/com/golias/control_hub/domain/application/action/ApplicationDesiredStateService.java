package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.DesiredSpec;
import com.golias.control_hub.domain.application.entities.ApplicationDesiredState;
import com.golias.control_hub.domain.application.exceptions.ApplicationNotFoundException;
import com.golias.control_hub.domain.application.repository.ApplicationDesiredStateRepo;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
public class ApplicationDesiredStateService {

    private final ApplicationRepo applicationRepo;
    private final ApplicationDesiredStateRepo desiredStateRepo;

    public ApplicationDesiredStateService(ApplicationRepo applicationRepo,
            ApplicationDesiredStateRepo desiredStateRepo) {
        this.applicationRepo = applicationRepo;
        this.desiredStateRepo = desiredStateRepo;
    }

    /** Declaring a desired state does not change the cluster; only the diff is affected. */
    public DesiredSpec set(UUID applicationId, DesiredSpec request) {
        requireRegistered(applicationId);
        validate(request);
        var saved = desiredStateRepo.save(ApplicationDesiredState.builder().applicationId(applicationId)
                .image(request.image()).replicas(request.replicas()).build());
        log.info("Desired state for application {} set to image={} replicas={}", applicationId, saved.getImage(),
                saved.getReplicas());
        return toSpec(saved);
    }

    public DesiredSpec get(UUID applicationId) {
        requireRegistered(applicationId);
        return desiredStateRepo.findById(applicationId).map(ApplicationDesiredStateService::toSpec)
                .orElseThrow(() -> new ApplicationNotFoundException(
                        "No desired state has been declared for application: " + applicationId));
    }

    public void clear(UUID applicationId) {
        requireRegistered(applicationId);
        desiredStateRepo.deleteById(applicationId);
    }

    private void requireRegistered(UUID applicationId) {
        if (!applicationRepo.existsById(applicationId)) {
            throw new ApplicationNotFoundException("Application not registered: " + applicationId);
        }
    }

    private void validate(DesiredSpec request) {
        if (request == null || (request.image() == null && request.replicas() == null)) {
            throw new IllegalArgumentException("desired state must declare image, replicas, or both");
        }
        if (request.image() != null && request.image().isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        if (request.replicas() != null && request.replicas() < 0) {
            throw new IllegalArgumentException("replicas must be zero or greater");
        }
    }

    private static DesiredSpec toSpec(ApplicationDesiredState state) {
        return new DesiredSpec(state.getImage(), state.getReplicas());
    }
}
