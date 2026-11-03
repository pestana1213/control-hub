package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.ApplicationHealth;
import com.golias.control_hub.domain.application.dto.StatusReason;
import com.golias.control_hub.domain.application.entities.ApplicationStatusEvent;
import com.golias.control_hub.domain.application.enums.ApplicationStatus;
import com.golias.control_hub.domain.application.enums.ApplicationStatusReason;
import com.golias.control_hub.domain.application.repository.ApplicationStatusEventRepo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.golias.control_hub.domain.application.enums.ApplicationStatusReason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationStatusEventRecorderTests {

    private static final UUID APPLICATION = UUID.randomUUID();

    private final ApplicationStatusEventRepo repository = mock(ApplicationStatusEventRepo.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ApplicationStatusEventRecorder recorder = new ApplicationStatusEventRecorder(repository, registry);

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void theFirstObservationHasNoPreviousStatus() {
        when(repository.findFirstByApplicationIdOrderByOccurredAtDesc(APPLICATION)).thenReturn(Optional.empty());

        recorder.record(APPLICATION, health(ApplicationStatus.HEALTHY));

        var saved = captureSaved();
        assertNull(saved.getFromStatus());
        assertEquals(ApplicationStatus.HEALTHY, saved.getToStatus());
        assertNull(saved.getReasonCode());
        assertEquals(APPLICATION, saved.getApplicationId());
    }

    @Test
    void repeatedIdenticalHealthIsNotRecordedAgain() {
        when(repository.findFirstByApplicationIdOrderByOccurredAtDesc(APPLICATION))
                .thenReturn(Optional.of(stored(ApplicationStatus.DEGRADED, REPLICA_NOT_READY)));

        recorder.record(APPLICATION, health(ApplicationStatus.DEGRADED, reason(REPLICA_NOT_READY)));

        verify(repository, never()).save(any());
    }

    @Test
    void aNewReasonWithinTheSameStatusIsStillRecorded() {
        when(repository.findFirstByApplicationIdOrderByOccurredAtDesc(APPLICATION))
                .thenReturn(Optional.of(stored(ApplicationStatus.DEGRADED, REPLICA_NOT_READY)));

        recorder.record(APPLICATION, health(ApplicationStatus.DEGRADED, reason(POD_CRASH_LOOP)));

        var saved = captureSaved();
        assertEquals(ApplicationStatus.DEGRADED, saved.getFromStatus());
        assertEquals(ApplicationStatus.DEGRADED, saved.getToStatus());
        assertEquals(POD_CRASH_LOOP, saved.getReasonCode());
    }

    @Test
    void theMostSevereReasonBecomesTheRecordedCause() {
        when(repository.findFirstByApplicationIdOrderByOccurredAtDesc(APPLICATION)).thenReturn(Optional.empty());

        recorder.record(APPLICATION, health(ApplicationStatus.DEGRADED, reason(ROLLOUT_IN_PROGRESS),
                reason(POD_CRASH_LOOP), reason(REPLICA_NOT_READY)));

        var saved = captureSaved();
        assertEquals(POD_CRASH_LOOP, saved.getReasonCode());
        assertTrue(saved.getMessage().contains("Deployment/production/api"), saved.getMessage());
    }

    @Test
    void recoveryToHealthyClearsTheReason() {
        when(repository.findFirstByApplicationIdOrderByOccurredAtDesc(APPLICATION))
                .thenReturn(Optional.of(stored(ApplicationStatus.UNHEALTHY, POD_CRASH_LOOP)));

        recorder.record(APPLICATION, health(ApplicationStatus.HEALTHY));

        var saved = captureSaved();
        assertEquals(ApplicationStatus.UNHEALTHY, saved.getFromStatus());
        assertEquals(ApplicationStatus.HEALTHY, saved.getToStatus());
        assertNull(saved.getReasonCode());
        assertNull(saved.getMessage());
    }

    @Test
    void anUnknownApplicationOrAbsentHealthIsIgnored() {
        recorder.record(null, health(ApplicationStatus.HEALTHY));
        recorder.record(APPLICATION, null);

        verifyNoInteractions(repository);
    }

    private ApplicationStatusEvent captureSaved() {
        var captor = ArgumentCaptor.forClass(ApplicationStatusEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private ApplicationStatusEvent stored(ApplicationStatus status, ApplicationStatusReason code) {
        return ApplicationStatusEvent.builder().applicationId(APPLICATION).toStatus(status).reasonCode(code).build();
    }

    private StatusReason reason(ApplicationStatusReason code) {
        return new StatusReason(code, "Deployment/production/api", "something is wrong");
    }

    private ApplicationHealth health(ApplicationStatus status, StatusReason... reasons) {
        return new ApplicationHealth(status, List.of(reasons), 1, 1, "ghcr.io/api:1.4.1", 0, List.of(),
                Instant.now());
    }
}
