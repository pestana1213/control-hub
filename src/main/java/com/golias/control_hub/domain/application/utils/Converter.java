package com.golias.control_hub.domain.application.utils;

import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import com.golias.control_hub.domain.application.action.ApplicationStatusService;
import com.golias.control_hub.domain.application.dto.EventDTO;
import com.golias.control_hub.domain.application.dto.IngressDTO;
import com.golias.control_hub.domain.application.dto.PodDTO;
import com.golias.control_hub.domain.application.dto.PodStatus;
import com.golias.control_hub.domain.application.entities.Application;
import io.kubernetes.client.openapi.models.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class Converter implements Converters {

    private final ApplicationStatusService statusService;

    public Converter(ApplicationStatusService statusService) {
        this.statusService = statusService;
    }

    public ApplicationDTO convert(Application application) {
        return ApplicationDTO.builder().name(application.getName()).namespace(application.getNamespace())
                .description(application.getDescription()).repositoryUrl(application.getRepositoryUrl()).build();
    }

    public PodDTO toPodDTO(V1Pod pod) {
        String name = pod.getMetadata() != null ? pod.getMetadata().getName() : "unknown";

        V1PodStatus podStatus = pod.getStatus();
        String phase = podStatus != null && podStatus.getPhase() != null ? podStatus.getPhase() : "Unknown";

        List<V1ContainerStatus> containerStatuses = podStatus != null ? podStatus.getContainerStatuses() : null;

        int restarts = 0;
        PodStatus status = statusService.evaluatePod(pod);
        if (containerStatuses != null && !containerStatuses.isEmpty()) {
            restarts = containerStatuses.stream().filter(Objects::nonNull).map(V1ContainerStatus::getRestartCount)
                    .filter(Objects::nonNull).mapToInt(Integer::intValue).sum();

        }

        return new PodDTO(name, status, phase, restarts);
    }

    public IngressDTO toIngressDTO(V1Ingress ingress) {

        String name = ingress.getMetadata().getName();

        if (ingress.getSpec() == null || ingress.getSpec().getRules() == null) {
            return new IngressDTO(name, List.of());
        }

        boolean https = ingress.getSpec().getTls() != null && !ingress.getSpec().getTls().isEmpty();

        String scheme = https ? "https" : "http";

        List<String> urls = ingress.getSpec().getRules().stream().map(V1IngressRule::getHost).filter(Objects::nonNull)
                .map(host -> scheme + "://" + host).toList();

        return new IngressDTO(name, urls);
    }

    public EventDTO toEventDTO(CoreV1Event event) {
        V1ObjectReference involvedObject = event.getInvolvedObject();

        return new EventDTO(event.getType(), event.getReason(), event.getMessage(), event.getCount(),
                event.getFirstTimestamp(), event.getLastTimestamp(), involvedObject.getKind(), involvedObject.getName(),
                getSourceComponent(event), getSourceHost(event));
    }

    private String getSourceComponent(CoreV1Event event) {
        if (event.getReportingComponent() != null) {
            return event.getReportingComponent();
        }

        if (event.getSource() != null) {
            return event.getSource().getComponent();
        }

        return null;
    }

    private String getSourceHost(CoreV1Event event) {
        if (event.getReportingInstance() != null) {
            return event.getReportingInstance();
        }

        if (event.getSource() != null) {
            return event.getSource().getHost();
        }

        return null;
    }
}
