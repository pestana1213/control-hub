package com.golias.control_hub.domain.operation.entrypoint;

import com.golias.control_hub.domain.operation.action.OperationExecutor;
import com.golias.control_hub.domain.operation.entities.Operation;
import com.golias.control_hub.domain.operation.entities.OperationType;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("v1/operations")
public class Operator {

    private final OperationExecutor executor;

    public Operator(OperationExecutor executor) {
        this.executor = executor;
    }

    @PostMapping
    public Operation executeOperation(@org.springframework.web.bind.annotation.RequestBody OperationRequest request) {
        return executor.executeOperation(request);
    }
}
