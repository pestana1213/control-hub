package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.operation.entities.OperationType;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import io.kubernetes.client.openapi.models.V1Deployment;

public interface OperationHandler {

    OperationType type();

    void execute(Application application, OperationRequest request);
}
