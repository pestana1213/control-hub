package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.repository.ApplicationStatusEventRepo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationStatusEventRetentionTests {

    private final ApplicationStatusEventRepo repository = mock(ApplicationStatusEventRepo.class);

    @Test
    void purgesEventsOlderThanTheRetentionWindow() {
        when(repository.deleteOccurredBefore(any())).thenReturn(3);

        new ApplicationStatusEventRetention(repository, Duration.ofDays(14)).purge();

        var captor = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(repository).deleteOccurredBefore(captor.capture());
        var cutoff = captor.getValue();
        assertTrue(cutoff.isBefore(ZonedDateTime.now().minusDays(13)), "cutoff was " + cutoff);
        assertTrue(cutoff.isAfter(ZonedDateTime.now().minusDays(15)), "cutoff was " + cutoff);
    }

    @Test
    void anEmptyPurgeIsHarmless() {
        when(repository.deleteOccurredBefore(any())).thenReturn(0);

        assertDoesNotThrow(() -> new ApplicationStatusEventRetention(repository, Duration.ofHours(1)).purge());
    }
}
