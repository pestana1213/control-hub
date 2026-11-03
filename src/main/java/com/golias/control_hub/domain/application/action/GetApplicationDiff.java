package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.ApplicationDiffDTO;
import com.golias.control_hub.domain.application.dto.DesiredSpec;
import com.golias.control_hub.domain.application.dto.ObservedSpec;
import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.enums.DriftStatus;
import com.golias.control_hub.domain.application.enums.DriftType;
import com.golias.control_hub.domain.application.exceptions.ApplicationNotFoundException;
import com.golias.control_hub.domain.application.repository.ApplicationDesiredStateRepo;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.application.utils.ObservedSpecs;
import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesObservedState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Compares the declared spec with what is running. Reporting only; nothing is reconciled.
 */
@Service
public class GetApplicationDiff {

    private final ApplicationRepo applicationRepo;
    private final ApplicationDesiredStateRepo desiredStateRepo;
    private final KubernetesObservedState observedState;

    public GetApplicationDiff(ApplicationRepo applicationRepo, ApplicationDesiredStateRepo desiredStateRepo,
            KubernetesObservedState observedState) {
        this.applicationRepo = applicationRepo;
        this.desiredStateRepo = desiredStateRepo;
        this.observedState = observedState;
    }

    public ApplicationDiffDTO getDiff(UUID applicationId) {
        Application application = applicationRepo.findById(applicationId).orElseThrow(
                () -> new ApplicationNotFoundException("Application not registered: " + applicationId));
        DesiredSpec desired = desiredStateRepo.findById(applicationId)
                .map(state -> new DesiredSpec(state.getImage(), state.getReplicas())).orElse(null);
        ObservedApplicationState state = observedState
                .get(new ApplicationKey(application.getNamespace(), application.getName())).orElse(null);

        // Without a Deployment there is nothing to read a spec from, so drift is unknown rather
        // than absent.
        boolean observable = state != null && ObservedSpecs.primary(state.deployment()) != null;
        ObservedSpec observed = observable ? ObservedSpecs.read(state.deployment()) : null;
        List<DriftType> drift = desired != null && observable ? detect(desired, observed) : List.of();

        DriftStatus status;
        if (desired == null) {
            status = DriftStatus.UNMANAGED;
        } else if (!observable) {
            status = DriftStatus.UNOBSERVED;
        } else {
            status = drift.isEmpty() ? DriftStatus.IN_SYNC : DriftStatus.DRIFTED;
        }

        return new ApplicationDiffDTO(application.getId(), application.getName(), application.getNamespace(), status,
                desired, observed, drift, state == null ? null : state.updatedAt());
    }

    private static List<DriftType> detect(DesiredSpec desired, ObservedSpec observed) {
        List<DriftType> drift = new ArrayList<>();
        if (desired.image() != null && !Objects.equals(desired.image(), observed.image())) {
            drift.add(DriftType.IMAGE);
        }
        if (desired.replicas() != null && !Objects.equals(desired.replicas(), observed.replicas())) {
            drift.add(DriftType.REPLICAS);
        }
        return List.copyOf(drift);
    }
}
