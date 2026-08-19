package com.golias.control_hub;

import com.golias.control_hub.domain.application.exceptions.ApplicationException;
import com.golias.control_hub.domain.kubernetes.exceptions.KubernetesException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private final Counter kubernetesExceptions;
    private final Counter applicationExceptions;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.kubernetesExceptions = meterRegistry.counter("control_hub.exceptions", "type", "kubernetes");
        this.applicationExceptions = meterRegistry.counter("control_hub.exceptions", "type", "application");
    }

    @ExceptionHandler(KubernetesException.class)
    public ProblemDetail handleKubernetesException(KubernetesException exception) {

        log.error("Kubernetes operation failed", exception);

        kubernetesExceptions.increment();

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);

        problem.setTitle("Kubernetes unavailable");
        problem.setDetail(exception.getMessage());

        return problem;
    }

    @ExceptionHandler(ApplicationException.class)
    public ProblemDetail handleApplicationException(ApplicationException exception) {

        log.error("Application operation failed", exception);

        applicationExceptions.increment();

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);

        problem.setTitle("Something went wrong while getting the application");
        problem.setDetail(exception.getMessage());

        return problem;
    }
}
