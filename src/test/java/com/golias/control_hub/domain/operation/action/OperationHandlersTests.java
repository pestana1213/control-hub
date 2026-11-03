package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.kubernetes.exceptions.RolloutTimeoutException;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesOperations;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesRollout;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import com.golias.control_hub.domain.operation.entities.OperationType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationHandlersTests {

    private final Application application = Application.builder().name("api").namespace("production").build();
    private final KubernetesOperations operations = mock(KubernetesOperations.class);
    private final KubernetesRollout rollout = mock(KubernetesRollout.class);

    @Test
    void restartConfirmsTheGenerationThePatchProduced() {
        when(operations.restart("production", "api")).thenReturn(7L);

        new RestartOperationHandler(operations, rollout).execute(application, request(OperationType.RESTART));

        var inOrder = inOrder(operations, rollout);
        inOrder.verify(operations).restart("production", "api");
        inOrder.verify(rollout).awaitReady("production", "api", 7L);
    }

    @Test
    void restartFailsWhenTheRolloutNeverBecomesReady() {
        when(operations.restart("production", "api")).thenReturn(7L);
        doThrow(new RolloutTimeoutException("Rollout of production/api did not become ready within 180s: "
                + "POD_CRASH_LOOP Pod/production/api-7f/api (Container repeatedly fails to start)")).when(rollout)
                        .awaitReady("production", "api", 7L);

        var handler = new RestartOperationHandler(operations, rollout);
        var request = request(OperationType.RESTART);

        var exception = assertThrows(RolloutTimeoutException.class, () -> handler.execute(application, request));
        assertTrue(exception.getMessage().contains("POD_CRASH_LOOP"), exception.getMessage());
    }

    @Test
    void aFailedPatchIsNeverConfirmed() {
        when(operations.restart("production", "api")).thenThrow(new IllegalStateException("Kubernetes unavailable"));

        var handler = new RestartOperationHandler(operations, rollout);
        var request = request(OperationType.RESTART);

        assertThrows(IllegalStateException.class, () -> handler.execute(application, request));
        verifyNoInteractions(rollout);
    }

    @Test
    void scaleConfirmsItsOwnGeneration() {
        when(operations.scale("production", "api", 3)).thenReturn(11L);

        new ScaleOperationHandler(operations, rollout).execute(application,
                new OperationRequest("production", "api", OperationType.SCALE, 3, null, null));

        verify(rollout).awaitReady("production", "api", 11L);
    }

    @Test
    void deployConfirmsItsOwnGeneration() {
        when(operations.deploy("production", "api", "ghcr.io/api:1.4.2")).thenReturn(12L);

        new DeployOperationHandler(operations, rollout).execute(application,
                new OperationRequest("production", "api", OperationType.DEPLOY, null, "ghcr.io/api:1.4.2", null));

        verify(rollout).awaitReady("production", "api", 12L);
    }

    @Test
    void invalidRequestsAreRejectedBeforeTouchingTheCluster() {
        var scale = new ScaleOperationHandler(operations, rollout);
        var deploy = new DeployOperationHandler(operations, rollout);
        var negative = new OperationRequest("production", "api", OperationType.SCALE, -1, null, null);
        var blankImage = new OperationRequest("production", "api", OperationType.DEPLOY, null, " ", null);

        assertThrows(IllegalArgumentException.class, () -> scale.execute(application, negative));
        assertThrows(IllegalArgumentException.class, () -> deploy.execute(application, blankImage));
        verifyNoInteractions(operations, rollout);
    }

    private OperationRequest request(OperationType type) {
        return new OperationRequest("production", "api", type, null, null, null);
    }
}
