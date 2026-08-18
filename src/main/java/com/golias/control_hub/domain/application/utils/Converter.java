package com.golias.control_hub.domain.application.utils;

import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import com.golias.control_hub.domain.application.dto.EventDTO;
import com.golias.control_hub.domain.application.dto.IngressDTO;
import com.golias.control_hub.domain.application.dto.PodDTO;
import com.golias.control_hub.domain.application.dto.PodStatus;
import com.golias.control_hub.domain.application.entities.Application;
import io.kubernetes.client.openapi.models.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

import static com.golias.control_hub.domain.application.dto.PodStatus.*;

@Component
public class Converter implements Converters {

    public ApplicationDTO convert(Application application) {
        return ApplicationDTO.builder()
                .name(application.getName())
                .namespace(application.getNamespace())
                .description(application.getDescription())
                .repositoryUrl(application.getRepositoryUrl())
            .build();
    }

    public PodDTO toPodDTO(V1Pod pod) {
        String name = pod.getMetadata() != null ? pod.getMetadata().getName() : "unknown";

        V1PodStatus podStatus = pod.getStatus();
        String phase = podStatus != null && podStatus.getPhase() != null ? podStatus.getPhase() : "Unknown";

        List<V1ContainerStatus> containerStatuses = podStatus != null ? podStatus.getContainerStatuses() : null;

        int restarts = 0;
        PodStatus status = UNKNOWN;
        if (containerStatuses != null && !containerStatuses.isEmpty()) {
            restarts = containerStatuses.stream()
                    .mapToInt(V1ContainerStatus::getRestartCount)
                    .sum();

            boolean allReady = containerStatuses.stream().allMatch(V1ContainerStatus::getReady);
            status = allReady ? HEALTHY : UNHEALTHY;
        }

        return new PodDTO(name, status, phase, restarts);
    }

    public IngressDTO toIngressDTO(V1Ingress ingress) {

        String name = ingress.getMetadata().getName();

        if (ingress.getSpec() == null ||
                ingress.getSpec().getRules() == null) {
            return new IngressDTO(name, List.of());
        }

        boolean https = ingress.getSpec().getTls() != null && !ingress.getSpec().getTls().isEmpty();

        String scheme = https ? "https" : "http";

        List<String> urls = ingress.getSpec()
                .getRules()
                .stream()
                .map(V1IngressRule::getHost)
                .filter(Objects::nonNull)
                .map(host -> scheme + "://" + host)
                .toList();

        return new IngressDTO(name, urls);
    }

    public EventDTO toEventDTO(CoreV1Event event) {
        V1ObjectReference involvedObject = event.getInvolvedObject();

        return new EventDTO(
                event.getType(),
                event.getReason(),
                event.getMessage(),
                event.getCount(),
                event.getFirstTimestamp(),
                event.getLastTimestamp(),
                involvedObject != null ? involvedObject.getKind() : null,
                involvedObject != null ? involvedObject.getName() : null
        );
    }
}
