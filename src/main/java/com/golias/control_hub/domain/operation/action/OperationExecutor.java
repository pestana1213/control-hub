package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.port.ApplicationPort;
import com.golias.control_hub.domain.operation.entities.Operation;
import com.golias.control_hub.domain.operation.entities.OperationStatus;
import com.golias.control_hub.domain.operation.entities.OperationType;
import com.golias.control_hub.domain.operation.dto.OperationRequest;
import com.golias.control_hub.domain.operation.repository.OperationRepository;
import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesObservedState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;

@Service
@Slf4j
public class OperationExecutor {

    private static final String METRIC_NAME = "control_hub.operation";

    private final ApplicationPort applicationPort;
    private final OperationRepository operationRepository;
    private final MeterRegistry meterRegistry;
    private final Map<OperationType, OperationHandler> handlers;
    private final KubernetesObservedState observedState;
    private final String allowedNamespaces;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<java.util.UUID, Object> applicationLocks = new ConcurrentHashMap<>();

    public OperationExecutor(ApplicationPort applicationPort, OperationRepository operationRepository,
            MeterRegistry meterRegistry, List<OperationHandler> handlers, KubernetesObservedState observedState,
            @Value("${control.hub.operations.allowed-namespaces:*}") String allowedNamespaces) {
        this.applicationPort = applicationPort;
        this.operationRepository = operationRepository;
        this.meterRegistry = meterRegistry;
        this.handlers = indexHandlers(handlers);
        this.observedState = observedState;
        this.allowedNamespaces = allowedNamespaces;
    }

    public Operation executeOperation(OperationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        OperationType type = Objects.requireNonNull(request.type(), "type must not be null");
        OperationHandler handler = handlers.get(type);
        if (handler == null) {
            throw new UnsupportedOperationException("Operation type is not implemented: " + type);
        }
        validateRequest(request);
        Application application = applicationPort.findApplication(request.namespace(), request.application());
        if (observedState != null
                && observedState.get(new ApplicationKey(request.namespace(), request.application())).isEmpty()) {
            throw new IllegalStateException("Application has not been discovered in Kubernetes yet");
        }
        Object lock = applicationLocks.computeIfAbsent(application.getId(), ignored -> new Object());
        synchronized (lock) {
            if (operationRepository.existsByApplicationIdAndStatusIn(application.getId(),
                    Set.of(OperationStatus.PENDING, OperationStatus.RUNNING))) {
                throw new IllegalStateException("Another operation is already in progress for this application");
            }
            Operation operation = operationRepository.save(Operation.builder().applicationId(application.getId()).type(type)
                    .status(OperationStatus.PENDING).build());
            executor.submit(() -> execute(operation, application, handler, request));
            return operation;
        }
    }

    private void execute(Operation operation, Application application, OperationHandler handler,
            OperationRequest request) {
        OperationType type = handler.type();
        Timer.Sample sample = Timer.start(meterRegistry);
        Operation current = operation;

        try {
            ZonedDateTime startedAt = ZonedDateTime.now();
            current = operationRepository
                    .save(current.toBuilder().status(OperationStatus.RUNNING).startedAt(startedAt).build());

            handler.execute(application, request);

            operationRepository.save(
                    current.toBuilder().completedAt(ZonedDateTime.now()).status(OperationStatus.COMPLETED).build());

            counter(type, "success").increment();
        } catch (RuntimeException exception) {
            counter(type, "failure").increment();
            markAsFailed(current, exception);
            throw exception;
        } finally {
            sample.stop(timer(type));
        }
    }

    public Operation getOperation(java.util.UUID id) {
        return operationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Operation not found: " + id));
    }

    private void validateRequest(OperationRequest request) {
        if (request.namespace() == null || request.namespace().isBlank() || request.application() == null
                || request.application().isBlank()) {
            throw new IllegalArgumentException("namespace and application are required");
        }
        if (!"*".equals(allowedNamespaces) && Set.of(allowedNamespaces.split(",")).stream().map(String::trim)
                .noneMatch(request.namespace()::equals)) {
            throw new IllegalArgumentException("Namespace is not allowed: " + request.namespace());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private void markAsFailed(Operation operation, RuntimeException cause) {
        if (operation == null) {
            return;
        }

        try {
            operationRepository.save(operation.toBuilder().completedAt(ZonedDateTime.now()).error(cause.getMessage())
                    .status(OperationStatus.ERROR).build());
        } catch (RuntimeException persistenceFailure) {
            cause.addSuppressed(persistenceFailure);
            log.error("Could not mark operation {} as failed", operation.getId(), persistenceFailure);
        }
    }

    private Counter counter(OperationType type, String outcome) {
        return Counter.builder(METRIC_NAME + ".requests").description("Number of application operations")
                .tags("type", metricTag(type), "outcome", outcome).register(meterRegistry);
    }

    private Timer timer(OperationType type) {
        return Timer.builder(METRIC_NAME + ".duration").description("Time spent processing an application operation")
                .tag("type", metricTag(type)).publishPercentileHistogram().register(meterRegistry);
    }

    private static String metricTag(OperationType type) {
        return type.name().toLowerCase();
    }

    private static Map<OperationType, OperationHandler> indexHandlers(List<OperationHandler> handlers) {
        EnumMap<OperationType, OperationHandler> indexedHandlers = new EnumMap<>(OperationType.class);

        for (OperationHandler handler : handlers) {
            OperationHandler duplicate = indexedHandlers.put(handler.type(), handler);
            if (duplicate != null) {
                throw new IllegalStateException("Multiple handlers registered for operation type " + handler.type());
            }
        }

        return Map.copyOf(indexedHandlers);
    }
}
