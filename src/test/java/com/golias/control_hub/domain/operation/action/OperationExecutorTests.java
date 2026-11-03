package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.exceptions.ApplicationException;
import com.golias.control_hub.domain.application.port.ApplicationPort;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesObservedState;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import com.golias.control_hub.domain.operation.entities.OperationType;
import com.golias.control_hub.domain.operation.repository.OperationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationExecutorTests {

    @Test
    void namespaceAndApplicationAllowlistsAreBothEnforced() {
        withExecutor("production", "api,worker", executor -> {
            assertEquals("Namespace is not allowed: kube-system",
                    reject(executor, "kube-system", "api").getMessage());
            assertEquals("Application is not allowed: pihole", reject(executor, "production", "pihole").getMessage());
            assertThrows(ApplicationException.class, () -> executor.executeOperation(request("production", "api")),
                    "an allowed request should get past validation to the registry lookup");
        });
    }

    @Test
    void wildcardAllowlistsPermitEveryApplication() {
        withExecutor("*", "*", executor -> assertThrows(ApplicationException.class,
                () -> executor.executeOperation(request("kube-system", "traefik"))));
    }

    @Test
    void duplicateAllowlistEntriesAreTolerated() {
        withExecutor("production, production", "api,api", executor -> assertThrows(ApplicationException.class,
                () -> executor.executeOperation(request("production", "api"))));
    }

    private IllegalArgumentException reject(OperationExecutor executor, String namespace, String application) {
        return assertThrows(IllegalArgumentException.class,
                () -> executor.executeOperation(request(namespace, application)));
    }

    private OperationRequest request(String namespace, String application) {
        return new OperationRequest(namespace, application, OperationType.RESTART, null, null, null);
    }

    private void withExecutor(String namespaces, String applications, Consumer<OperationExecutor> test) {
        var applicationPort = mock(ApplicationPort.class);
        var repository = mock(OperationRepository.class);
        var observedState = mock(KubernetesObservedState.class);
        var handler = mock(OperationHandler.class);
        var registry = new SimpleMeterRegistry();
        when(handler.type()).thenReturn(OperationType.RESTART);
        when(applicationPort.findApplication(anyString(), anyString()))
                .thenThrow(new ApplicationException("Application not registered"));
        var executor = new OperationExecutor(applicationPort, repository, registry, List.of(handler), observedState,
                namespaces, applications);
        try {
            test.accept(executor);
        } finally {
            executor.shutdown();
            registry.close();
        }
    }
}
