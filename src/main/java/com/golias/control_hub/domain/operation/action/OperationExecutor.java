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
import java.util.Objects;
import java.util.function.Consumer;

@Service
@Slf4j
public class OperationExecutor {

    private static final String METRIC_NAME = "control_hub.operation";

    private final ApplicationPort applicationPort;
    private final OperationRepository operationRepository;
    private final MeterRegistry meterRegistry;

    public OperationExecutor(ApplicationPort applicationPort, OperationRepository operationRepository,
            MeterRegistry meterRegistry) {
        this.applicationPort = applicationPort;
        this.operationRepository = operationRepository;
        this.meterRegistry = meterRegistry;
    }

    public void execute(String namespace, String name, OperationType type, Consumer<Application> action) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(action, "action must not be null");

        Timer.Sample sample = Timer.start(meterRegistry);
        Operation operation = null;

        try {
            Application application = applicationPort.findApplication(namespace, name);
            ZonedDateTime startedAt = ZonedDateTime.now();

            operation = operationRepository.save(Operation.builder()
                    .applicationId(application.getId())
                    .type(type)
                    .status(OperationStatus.PENDING)
                    .requestedAt(startedAt)
                    .startedAt(startedAt)
                    .build());

            log.info("Starting {} operation {} for application {} in namespace {}", type, operation.getId(), name,
                    namespace);
            action.accept(application);

            operationRepository.save(operation.toBuilder()
                    .completedAt(ZonedDateTime.now())
                    .status(OperationStatus.COMPLETED)
                    .build());
            counter(type, "success").increment();
            log.info("Completed {} operation {} for application {} in namespace {}", type, operation.getId(), name,
                    namespace);
        } catch (RuntimeException e) {
            counter(type, "failure").increment();
            markAsFailed(operation, e);
            log.error("{} operation failed for application {} in namespace {}{}", type, name, namespace,
                    operation == null ? "" : " (operation " + operation.getId() + ")", e);
            throw e;
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
}
