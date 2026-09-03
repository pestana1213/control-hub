package com.golias.control_hub.domain.operation.entrypoint;

import com.golias.control_hub.domain.operation.action.OperationExecutor;
import com.golias.control_hub.domain.operation.entities.Operation;
import com.golias.control_hub.domain.operation.entities.OperationType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("v1/operations")
public class Operator {

    private final OperationExecutor executor;

    public Operator(OperationExecutor executor) {
        this.executor = executor;
    }

    @PostMapping
    public Operation restartApplication(@RequestParam String namespace, @RequestParam String application, @RequestParam OperationType type) {
        return executor.executeOperation(namespace, application, type);
    }
}
