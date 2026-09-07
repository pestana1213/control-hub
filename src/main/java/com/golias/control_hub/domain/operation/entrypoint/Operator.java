package com.golias.control_hub.domain.operation.entrypoint;

import com.golias.control_hub.domain.operation.action.OperationExecutor;
import com.golias.control_hub.domain.operation.entities.Operation;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("v1/operations")
public class Operator {

    private final OperationExecutor executor;

    public Operator(OperationExecutor executor) {
        this.executor = executor;
    }

    @PostMapping
    public ResponseEntity<Operation> executeOperation(
            @org.springframework.web.bind.annotation.RequestBody OperationRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(executor.executeOperation(request));
    }

    @GetMapping("/{id}")
    public Operation getOperation(@PathVariable UUID id) {
        return executor.getOperation(id);
    }
}
