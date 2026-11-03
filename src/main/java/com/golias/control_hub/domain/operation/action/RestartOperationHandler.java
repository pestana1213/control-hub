package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesOperations;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesRollout;
import com.golias.control_hub.domain.operation.entities.OperationType;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import org.springframework.stereotype.Component;

@Component
public class RestartOperationHandler implements OperationHandler {

    private final KubernetesOperations kubernetesOperations;
    private final KubernetesRollout rollout;

    public RestartOperationHandler(KubernetesOperations kubernetesOperations, KubernetesRollout rollout) {
        this.kubernetesOperations = kubernetesOperations;
        this.rollout = rollout;
    }

    @Override
    public OperationType type() {
        return OperationType.RESTART;
    }

    /**
     * Blocks until the new revision is ready, so the operation only reports COMPLETED once the
     * application has actually recovered. The caller runs on a virtual thread and polls
     * {@code GET /v1/operations/{id}} meanwhile.
     */
    @Override
    public void execute(Application application, OperationRequest request) {
        long generation = kubernetesOperations.restart(application.getNamespace(), application.getName());
        rollout.awaitReady(application.getNamespace(), application.getName(), generation);
    }
}
