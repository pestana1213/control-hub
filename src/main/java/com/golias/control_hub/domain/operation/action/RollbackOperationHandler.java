package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesOperations;
import com.golias.control_hub.domain.operation.entities.OperationType;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import org.springframework.stereotype.Component;

@Component
public class RollbackOperationHandler implements OperationHandler {
    private final KubernetesOperations operations;

    public RollbackOperationHandler(KubernetesOperations operations) {
        this.operations = operations;
    }

    @Override
    public OperationType type() {
        return OperationType.ROLLBACK;
    }

    @Override
    public void execute(Application application, OperationRequest request) {
        if (request.revision() == null || request.revision() < 1) {
            throw new IllegalArgumentException("revision must be positive for ROLLBACK");
        }
        operations.rollback(application.getNamespace(), application.getName(), request.revision());
    }
}
