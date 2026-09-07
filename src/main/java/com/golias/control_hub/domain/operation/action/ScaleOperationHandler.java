package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesOperations;
import com.golias.control_hub.domain.operation.entities.OperationType;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import org.springframework.stereotype.Component;

@Component
public class ScaleOperationHandler implements OperationHandler {
    private final KubernetesOperations operations;

    public ScaleOperationHandler(KubernetesOperations operations) {
        this.operations = operations;
    }

    @Override
    public OperationType type() {
        return OperationType.SCALE;
    }

    @Override
    public void execute(Application application, OperationRequest request) {
        if (request.replicas() == null || request.replicas() < 0) {
            throw new IllegalArgumentException("replicas must be zero or greater for SCALE");
        }
        operations.scale(application.getNamespace(), application.getName(), request.replicas());
    }
}
