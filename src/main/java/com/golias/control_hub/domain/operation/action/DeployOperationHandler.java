package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesOperations;
import com.golias.control_hub.domain.operation.entities.OperationType;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import org.springframework.stereotype.Component;

@Component
public class DeployOperationHandler implements OperationHandler {
    private final KubernetesOperations operations;

    public DeployOperationHandler(KubernetesOperations operations) {
        this.operations = operations;
    }

    @Override
    public OperationType type() {
        return OperationType.DEPLOY;
    }

    @Override
    public void execute(Application application, OperationRequest request) {
        if (request.image() == null || request.image().isBlank()) {
            throw new IllegalArgumentException("image is required for DEPLOY");
        }
        operations.deploy(application.getNamespace(), application.getName(), request.image());
    }
}
