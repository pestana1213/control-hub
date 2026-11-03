package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.entities.ApplicationStatusEvent;
import com.golias.control_hub.domain.application.enums.ApplicationStatus;
import com.golias.control_hub.domain.application.enums.ApplicationStatusReason;
import com.golias.control_hub.domain.application.exceptions.ApplicationNotFoundException;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import com.golias.control_hub.domain.application.repository.ApplicationStatusEventRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetApplicationStatusEventsTests {

    private static final UUID APPLICATION = UUID.randomUUID();

    private final ApplicationRepo applicationRepo = mock(ApplicationRepo.class);
    private final ApplicationStatusEventRepo eventRepo = mock(ApplicationStatusEventRepo.class);
    private final GetApplicationStatusEvents service = new GetApplicationStatusEvents(applicationRepo, eventRepo, 50);

    @BeforeEach
    void setUp() {
        when(applicationRepo.existsById(APPLICATION)).thenReturn(true);
        when(eventRepo.findByApplicationIdOrderByOccurredAtDesc(eq(APPLICATION), any())).thenReturn(List.of());
    }

    @Test
    void unregisteredApplicationIsNotFound() {
        var unknown = UUID.randomUUID();
        when(applicationRepo.existsById(unknown)).thenReturn(false);

        assertThrows(ApplicationNotFoundException.class, () -> service.getStatusEvents(unknown, null));
        verifyNoInteractions(eventRepo);
    }

    @Test
    void noLimitFallsBackToTheConfiguredMaximum() {
        service.getStatusEvents(APPLICATION, null);
        assertEquals(50, capturedLimit().max());
    }

    @Test
    void anOversizedLimitIsCappedAndZeroIsRaised() {
        service.getStatusEvents(APPLICATION, 500);
        assertEquals(50, capturedLimit().max());

        reset(eventRepo);
        when(eventRepo.findByApplicationIdOrderByOccurredAtDesc(eq(APPLICATION), any())).thenReturn(List.of());
        service.getStatusEvents(APPLICATION, 0);
        assertEquals(1, capturedLimit().max());
    }

    @Test
    void storedTransitionsAreExposedAsTheyWereRecorded() {
        var occurredAt = ZonedDateTime.now();
        when(eventRepo.findByApplicationIdOrderByOccurredAtDesc(eq(APPLICATION), any()))
                .thenReturn(List.of(ApplicationStatusEvent.builder().applicationId(APPLICATION)
                        .fromStatus(ApplicationStatus.HEALTHY).toStatus(ApplicationStatus.DEGRADED)
                        .reasonCode(ApplicationStatusReason.REPLICA_NOT_READY)
                        .message("Deployment/production/api: 1 of 2 replicas ready").occurredAt(occurredAt).build()));

        var event = service.getStatusEvents(APPLICATION, 10).getFirst();

        assertEquals(ApplicationStatus.HEALTHY, event.from());
        assertEquals(ApplicationStatus.DEGRADED, event.to());
        assertEquals(ApplicationStatusReason.REPLICA_NOT_READY, event.reasonCode());
        assertEquals("Deployment/production/api: 1 of 2 replicas ready", event.message());
        assertEquals(occurredAt, event.occurredAt());
    }

    private Limit capturedLimit() {
        var captor = ArgumentCaptor.forClass(Limit.class);
        verify(eventRepo).findByApplicationIdOrderByOccurredAtDesc(eq(APPLICATION), captor.capture());
        return captor.getValue();
    }
}
