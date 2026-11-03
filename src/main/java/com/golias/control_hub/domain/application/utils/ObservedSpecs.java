package com.golias.control_hub.domain.application.utils;

import com.golias.control_hub.domain.application.dto.ObservedSpec;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Deployment;

import java.util.List;
import java.util.Objects;

/**
 * Reads the few fields Control Hub treats as an application's spec, so health evaluation and
 * desired-state drift describe an application the same way.
 */
public final class ObservedSpecs {

    private ObservedSpecs() {
    }

    public static ObservedSpec read(List<V1Deployment> deployments) {
        V1Deployment primary = primary(deployments);
        return new ObservedSpec(image(primary), replicas(primary));
    }

    /** The deployment an application's spec is read from when several carry the same label. */
    public static V1Deployment primary(List<V1Deployment> deployments) {
        return deployments == null ? null
                : deployments.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * The image of the container named after the deployment, falling back to the first container.
     * DEPLOY patches the container by that same rule, so both agree on which image is the
     * application's.
     */
    public static String image(V1Deployment deployment) {
        if (deployment == null || deployment.getSpec() == null || deployment.getSpec().getTemplate() == null
                || deployment.getSpec().getTemplate().getSpec() == null) {
            return null;
        }
        List<V1Container> containers = deployment.getSpec().getTemplate().getSpec().getContainers();
        if (containers == null) {
            return null;
        }
        List<V1Container> present = containers.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return null;
        }
        String name = deployment.getMetadata() == null ? null : deployment.getMetadata().getName();
        return present.stream().filter(container -> name != null && name.equals(container.getName())).findFirst()
                .orElse(present.getFirst()).getImage();
    }

    public static Integer replicas(V1Deployment deployment) {
        return deployment == null || deployment.getSpec() == null ? null : deployment.getSpec().getReplicas();
    }
}
