package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesOperations;
import com.golias.control_hub.domain.operation.entities.OperationType;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import org.springframework.stereotype.Component;

@Component
public class RestartOperationHandler implements OperationHandler {

    private final KubernetesOperations kubernetesOperations;

    public RestartOperationHandler(KubernetesOperations kubernetesOperations) {
        this.kubernetesOperations = kubernetesOperations;
    }

    @Override
    public OperationType type() {
        return OperationType.RESTART;
    }

    @Override
    public void execute(Application application, OperationRequest request) {
        kubernetesOperations.restart(application.getNamespace(), application.getName());
    }
}
