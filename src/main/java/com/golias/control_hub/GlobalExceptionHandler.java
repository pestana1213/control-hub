package com.golias.control_hub;

import com.golias.control_hub.domain.application.exceptions.ApplicationException;
import com.golias.control_hub.domain.kubernetes.exceptions.KubernetesException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(KubernetesException.class)
    public ProblemDetail handleKubernetesException(
            KubernetesException exception) {

        log.error("Kubernetes operation failed", exception);

        meterRegistry.counter("control_hub.exceptions", "type", "kubernetes").increment();

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);

        problem.setTitle("Kubernetes unavailable");
        problem.setDetail(exception.getMessage());

        return problem;
    }

    @ExceptionHandler(ApplicationException.class)
    public ProblemDetail handleApplicationException(
            ApplicationException exception) {

        log.error("Application operation failed", exception);

        meterRegistry.counter("control_hub.exceptions", "type", "application").increment();

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);

        problem.setTitle("Something went wrong while getting the application");
        problem.setDetail(exception.getMessage());

        return problem;
    }
}