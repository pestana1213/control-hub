package com.golias.control_hub.domain.application.utils;

import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import com.golias.control_hub.domain.application.action.ApplicationStatusService;
import com.golias.control_hub.domain.application.dto.EventDTO;
import com.golias.control_hub.domain.application.dto.IngressDTO;
import com.golias.control_hub.domain.application.dto.PodDTO;
import com.golias.control_hub.domain.application.enums.PodStatus;
import com.golias.control_hub.domain.application.entities.Application;
import io.kubernetes.client.openapi.models.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Component
@Slf4j
public class Converter implements Converters {

    private final ApplicationStatusService statusService;

    public Converter(ApplicationStatusService statusService) {
        this.statusService = statusService;
    }

    public ApplicationDTO convert(Application application) {
        return ApplicationDTO.builder().id(application.getId()).name(application.getName())
                .namespace(application.getNamespace()).description(application.getDescription())
                .repositoryUrl(application.getRepositoryUrl()).build();
    }

    public PodDTO toPodDTO(V1Pod pod) {
        String name = pod.getMetadata() != null ? pod.getMetadata().getName() : "unknown";

        V1PodStatus podStatus = pod.getStatus();
        String phase = podStatus != null && podStatus.getPhase() != null ? podStatus.getPhase() : "Unknown";

        PodStatus status = statusService.evaluatePod(pod);

        // Init containers are counted too, so this matches ApplicationHealth.restartCount.
        int restarts = podStatus == null ? 0
                : Stream.concat(containerStatuses(podStatus.getInitContainerStatuses()),
                        containerStatuses(podStatus.getContainerStatuses()))
                        .mapToInt(container -> container.getRestartCount() == null ? 0 : container.getRestartCount())
                        .sum();

        return new PodDTO(name, status, phase, restarts);
    }

    public IngressDTO toIngressDTO(V1Ingress ingress) {
        String name = ingress.getMetadata().getName();

        if (ingress.getStatus() == null || ingress.getStatus().getLoadBalancer() == null || ingress.getStatus().getLoadBalancer().getIngress() == null) {
            return new IngressDTO(name, List.of());
        }

        List<String> urls = ingress.getStatus().getLoadBalancer().getIngress().stream().map(V1IngressLoadBalancerIngress::getHostname).filter(Objects::nonNull)
                .map(host -> "https://" + host).toList();

        return new IngressDTO(name, urls);
    }

    public EventDTO toEventDTO(CoreV1Event event) {
        V1ObjectReference involvedObject = event.getInvolvedObject();

        return new EventDTO(event.getType(), event.getReason(), event.getMessage(), event.getCount(),
                event.getFirstTimestamp(), event.getLastTimestamp(), involvedObject.getKind(), involvedObject.getName(),
                getSourceComponent(event), getSourceHost(event));
    }

    private Stream<V1ContainerStatus> containerStatuses(List<V1ContainerStatus> statuses) {
        return statuses == null ? Stream.empty() : statuses.stream().filter(Objects::nonNull);
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
