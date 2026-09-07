package com.golias.control_hub.domain.operation.entities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationTypeTests {

    @Test
    void includesTheOperationsDefinedByTheDomain() {
        assertTrue(java.util.Set.of(OperationType.values()).containsAll(java.util.Set.of(
                OperationType.RESTART, OperationType.SCALE, OperationType.DEPLOY,
                OperationType.SYNC, OperationType.ROLLBACK)));
    }
}
