package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.kubernetes.ports.KubernetesOperations;
import com.golias.control_hub.domain.operation.entities.OperationType;
import org.springframework.stereotype.Service;

@Service
public class RestartApp {

    private final OperationExecutor operationExecutor;
    private final KubernetesOperations kubernetesOperations;

    public RestartApp(OperationExecutor operationExecutor, KubernetesOperations kubernetesOperations) {
        this.operationExecutor = operationExecutor;
        this.kubernetesOperations = kubernetesOperations;
    }

    public void restart(String namespace, String name) {
        operationExecutor.execute(namespace, name, OperationType.RESTART,
                application -> kubernetesOperations.restart(application.getNamespace(), application.getName()));
    }
}
