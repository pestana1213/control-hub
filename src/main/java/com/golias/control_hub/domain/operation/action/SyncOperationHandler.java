package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesOperations;
import com.golias.control_hub.domain.operation.entities.OperationType;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import org.springframework.stereotype.Component;

@Component
public class SyncOperationHandler implements OperationHandler {
    private final KubernetesOperations operations;

    public SyncOperationHandler(KubernetesOperations operations) {
        this.operations = operations;
    }

    @Override
    public OperationType type() {
        return OperationType.SYNC;
    }

    @Override
    public void execute(Application application, OperationRequest request) {
        operations.sync(application.getNamespace(), application.getName());
    }
}
