package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.*;
import com.golias.control_hub.domain.application.enums.ApplicationStatus;
import com.golias.control_hub.domain.application.enums.PodStatus;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;
import io.kubernetes.client.openapi.models.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static com.golias.control_hub.domain.application.enums.ApplicationStatusReason.*;

@Service
public class ApplicationStatusService {

    public ApplicationHealth evaluate(ObservedApplicationState state) {
        List<StatusReason> reasons = new ArrayList<>();
        if (state == null || state.deployment() == null || state.deployment().isEmpty()) {
            return new ApplicationHealth(ApplicationStatus.UNKNOWN, List
                    .of(new StatusReason(OBSERVED_STATE_UNAVAILABLE, "application", "No deployment state available")),
                    0, 0);
        }
        int desired = 0;
        int ready = 0;
        boolean incomplete = false;
        for (var deployment : state.deployment()) {
            if (deployment == null || deployment.getSpec() == null || deployment.getSpec().getReplicas() == null) {
                incomplete = true;
                reasons.add(new StatusReason(OBSERVED_STATE_UNAVAILABLE, "deployment",
                        "Desired replica count unavailable"));
                continue;
            }
            int target = deployment.getSpec().getReplicas();
            desired += target;
            var status = deployment.getStatus();
            String resource = resourceName("Deployment", deployment.getMetadata());
            if (status == null || status.getObservedGeneration() == null) {
                incomplete = true;
                reasons.add(new StatusReason(OBSERVED_STATE_UNAVAILABLE, resource,
                        "Deployment status has not been observed"));
                continue;
            }
            int available = count(status.getReadyReplicas());
            ready += available;
            if (available < target) {
                reasons.add(
                        new StatusReason(REPLICA_NOT_READY, resource, available + " of " + target + " replicas ready"));
            }
            Long generation = deployment.getMetadata() == null ? null : deployment.getMetadata().getGeneration();
            if ((generation != null && status.getObservedGeneration() < generation)
                    || count(status.getUpdatedReplicas()) < target || count(status.getReplicas()) > target) {
                reasons.add(new StatusReason(ROLLOUT_IN_PROGRESS, resource,
                        "Deployment has not converged to its desired revision"));
            }
        }
        if (state.pods() != null) {
            for (var pod : state.pods()) {
                if (pod == null || pod.getStatus() == null || isDeleting(pod)) {
                    continue;
                }
                containers(pod.getStatus()).forEach(container -> {
                    var waiting = container.getState() == null ? null : container.getState().getWaiting();
                    String reason = waiting == null ? null : waiting.getReason();
                    String resource = resourceName("Pod", pod.getMetadata()) + "/" + container.getName();
                    if ("CrashLoopBackOff".equals(reason)) {
                        reasons.add(new StatusReason(POD_CRASH_LOOP, resource, "Container repeatedly fails to start"));
                    } else if ("ImagePullBackOff".equals(reason) || "ErrImagePull".equals(reason)
                            || "InvalidImageName".equals(reason)) {
                        reasons.add(new StatusReason(IMAGE_PULL_ERROR, resource,
                                "Container image could not be pulled: " + reason));
                    }
                });
            }
        }
        boolean failure = reasons.stream()
                .anyMatch(reason -> reason.code() == POD_CRASH_LOOP || reason.code() == IMAGE_PULL_ERROR);
        ApplicationStatus result;
        if (incomplete) {
            result = ApplicationStatus.UNKNOWN;
        } else if (desired > 0 && ready == 0 && failure) {
            result = ApplicationStatus.UNHEALTHY;
        } else if (!reasons.isEmpty()) {
            result = ApplicationStatus.DEGRADED;
        } else {
            result = ApplicationStatus.HEALTHY;
        }
        return new ApplicationHealth(result, List.copyOf(reasons), desired, ready);
    }

    public PodStatus evaluatePod(V1Pod pod) {
        if (pod == null || pod.getStatus() == null) {
            return PodStatus.UNKNOWN;
        }
        if (isDeleting(pod)) {
            return PodStatus.UNHEALTHY;
        }
        var conditions = pod.getStatus().getConditions();
        if (conditions == null) {
            return PodStatus.UNKNOWN;
        }
        return conditions.stream().filter(condition -> "Ready".equals(condition.getType())).findFirst()
                .map(condition -> "True".equals(condition.getStatus()) ? PodStatus.HEALTHY
                        : "False".equals(condition.getStatus()) ? PodStatus.UNHEALTHY : PodStatus.UNKNOWN)
                .orElse(PodStatus.UNKNOWN);
    }

    private Stream<V1ContainerStatus> containers(V1PodStatus status) {
        return Stream.concat(safe(status.getInitContainerStatuses()), safe(status.getContainerStatuses()));
    }

    private Stream<V1ContainerStatus> safe(List<V1ContainerStatus> statuses) {
        return statuses == null ? Stream.empty() : statuses.stream().filter(Objects::nonNull);
    }

    private boolean isDeleting(V1Pod pod) {
        return pod.getMetadata() != null && pod.getMetadata().getDeletionTimestamp() != null;
    }

    private int count(Integer count) {
        return count == null ? 0 : count;
    }

    private String resourceName(String kind, V1ObjectMeta metadata) {
        return kind + "/" + (metadata == null ? "unknown" : metadata.getNamespace() + "/" + metadata.getName());
    }
}
