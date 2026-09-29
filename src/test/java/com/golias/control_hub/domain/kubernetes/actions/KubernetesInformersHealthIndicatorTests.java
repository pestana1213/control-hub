package com.golias.control_hub.domain.kubernetes.actions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KubernetesInformersHealthIndicatorTests {

    @Test
    void reportsUpWhenInformersAreRunningAndSynced() {
        var informers = mock(KubernetesResourceInformers.class);
        when(informers.isRunning()).thenReturn(true);
        when(informers.hasSynced()).thenReturn(true);

        var health = new KubernetesInformersHealthIndicator(informers).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("synced", health.getDetails().get("informers"));
    }

    @Test
    void reportsDownWhileInformerCachesAreStillSyncing() {
        var informers = mock(KubernetesResourceInformers.class);
        when(informers.isRunning()).thenReturn(true);
        when(informers.hasSynced()).thenReturn(false);

        var health = new KubernetesInformersHealthIndicator(informers).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("syncing", health.getDetails().get("informers"));
    }

    @Test
    void reportsDownWhenInformersAreNotRunning() {
        var informers = mock(KubernetesResourceInformers.class);
        when(informers.isRunning()).thenReturn(false);

        var health = new KubernetesInformersHealthIndicator(informers).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("not running", health.getDetails().get("informers"));
    }
}
