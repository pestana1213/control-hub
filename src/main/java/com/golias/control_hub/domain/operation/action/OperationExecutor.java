package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.port.ApplicationPort;
import com.golias.control_hub.domain.operation.entities.Operation;
import com.golias.control_hub.domain.operation.entities.OperationStatus;
import com.golias.control_hub.domain.operation.entities.OperationType;
import com.golias.control_hub.domain.operation.repository.OperationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class OperationExecutor {

    private static final String METRIC_NAME = "control_hub.operation";

    private final ApplicationPort applicationPort;
    private final OperationRepository operationRepository;
    private final MeterRegistry meterRegistry;
    private final Map<OperationType, OperationHandler> handlers;

    public OperationExecutor(ApplicationPort applicationPort, OperationRepository operationRepository,
            MeterRegistry meterRegistry, List<OperationHandler> handlers) {
        this.applicationPort = applicationPort;
        this.operationRepository = operationRepository;
        this.meterRegistry = meterRegistry;
        this.handlers = indexHandlers(handlers);
    }

    public Operation executeOperation(String namespace, String applicationName, OperationType type) {
        Objects.requireNonNull(type, "type must not be null");
        return execute(namespace, applicationName, handlers.get(type));
    }

    private Operation execute(String namespace, String applicationName, OperationHandler handler) {
        OperationType type = handler.type();
        Timer.Sample sample = Timer.start(meterRegistry);
        Operation operation = null;

        try {
            Application application = applicationPort.findApplication(namespace, applicationName);
            ZonedDateTime startedAt = ZonedDateTime.now();

            operation = operationRepository.save(Operation.builder()
                    .applicationId(application.getId())
                    .type(type)
                    .status(OperationStatus.PENDING)
                    .requestedAt(startedAt)
                    .startedAt(startedAt)
                    .build());

            handler.execute(application);

            Operation completedOperation = operationRepository.save(operation.toBuilder()
                    .completedAt(ZonedDateTime.now())
                    .status(OperationStatus.COMPLETED)
                    .build());

            counter(type, "success").increment();
            return completedOperation;
        } catch (RuntimeException exception) {
            counter(type, "failure").increment();
            markAsFailed(operation, exception);
            throw exception;
        } finally {
            sample.stop(timer(type));
        }
    }

    private void markAsFailed(Operation operation, RuntimeException cause) {
        if (operation == null) {
            return;
        }

        try {
            operationRepository.save(operation.toBuilder()
                    .completedAt(ZonedDateTime.now())
                    .error(cause.getMessage())
                    .status(OperationStatus.ERROR)
                    .build());
        } catch (RuntimeException persistenceFailure) {
            cause.addSuppressed(persistenceFailure);
            log.error("Could not mark operation {} as failed", operation.getId(), persistenceFailure);
        }
    }

    private Counter counter(OperationType type, String outcome) {
        return Counter.builder(METRIC_NAME + ".requests")
                .description("Number of application operations")
                .tags("type", metricTag(type), "outcome", outcome)
                .register(meterRegistry);
    }

    private Timer timer(OperationType type) {
        return Timer.builder(METRIC_NAME + ".duration")
                .description("Time spent processing an application operation")
                .tag("type", metricTag(type))
                .publishPercentileHistogram()
                .register(meterRegistry);
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

        for (OperationType type : OperationType.values()) {
            if (!indexedHandlers.containsKey(type)) {
                throw new IllegalStateException("No handler registered for operation type " + type);
            }
        }

        return Map.copyOf(indexedHandlers);
    }
}
