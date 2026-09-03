package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.operation.entities.OperationType;

public interface OperationHandler {

    OperationType type();

    void execute(Application application);
}
