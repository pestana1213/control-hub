package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.ApplicationHealth;
import com.golias.control_hub.domain.application.dto.StatusReason;
import com.golias.control_hub.domain.application.entities.ApplicationStatusEvent;
import com.golias.control_hub.domain.application.enums.ApplicationStatusReason;
import com.golias.control_hub.domain.application.repository.ApplicationStatusEventRepo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static com.golias.control_hub.domain.application.enums.ApplicationStatusReason.*;

/**
 * Appends a row only when an application's health actually changes. The reconciler runs on every
 * informer event, so without this gate a single restart would write hundreds of near-identical
 * rows.
 */
@Service
@Slf4j
public class ApplicationStatusEventRecorder {

    /** Most to least severe. The first reason present becomes the recorded cause. */
    private static final List<ApplicationStatusReason> SEVERITY = List.of(POD_CRASH_LOOP, IMAGE_PULL_ERROR,
            PROGRESS_DEADLINE_EXCEEDED, REPLICA_FAILURE, OBSERVED_STATE_UNAVAILABLE, REPLICA_NOT_READY,
            ROLLOUT_IN_PROGRESS);

    private final ApplicationStatusEventRepo repository;
    private final MeterRegistry meterRegistry;

    public ApplicationStatusEventRecorder(ApplicationStatusEventRepo repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    public void record(UUID applicationId, ApplicationHealth health) {
        if (applicationId == null || health == null) {
            return;
        }
        StatusReason cause = primaryReason(health);
        ApplicationStatusReason code = cause == null ? null : cause.code();
        ApplicationStatusEvent last = repository.findFirstByApplicationIdOrderByOccurredAtDesc(applicationId)
                .orElse(null);

        // The reason is part of the signature, so DEGRADED(REPLICA_NOT_READY) becoming
        // DEGRADED(POD_CRASH_LOOP) is still worth recording.
        if (last != null && last.getToStatus() == health.status() && last.getReasonCode() == code) {
            return;
        }

        repository.save(ApplicationStatusEvent.builder().applicationId(applicationId)
                .fromStatus(last == null ? null : last.getToStatus()).toStatus(health.status()).reasonCode(code)
                .message(cause == null ? null : cause.resource() + ": " + cause.message()).build());
        Counter.builder("control_hub.application.status_event.recorded")
                .description("Number of recorded application health transitions")
                .tag("status", health.status().name().toLowerCase()).register(meterRegistry).increment();
        log.info("Application {} transitioned {} -> {}{}", applicationId,
                last == null ? "NEW" : last.getToStatus(), health.status(), code == null ? "" : " (" + code + ")");
    }

    private StatusReason primaryReason(ApplicationHealth health) {
        return SEVERITY.stream()
                .flatMap(code -> health.reasons().stream().filter(reason -> reason.code() == code)).findFirst()
                .orElse(null);
    }
}
