package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.state.ReconciliationQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconciliationWorkerTests {
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @org.junit.jupiter.api.AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void processesOtherApplicationsWhileARetryIsScheduled() {
        var informers = mock(KubernetesResourceInformers.class);
        when(informers.hasSynced()).thenReturn(true);
        var reconciler = mock(KubernetesReconciler.class);
        var queue = new ReconciliationQueue(registry);
        var failing = new ApplicationKey("production", "failing");
        var healthy = new ApplicationKey("production", "healthy");
        when(reconciler.reconcile(failing)).thenAnswer(invocation -> {
            queue.enqueue(healthy);
            throw new IllegalStateException("Database unavailable");
        });
        queue.enqueue(failing);
        var worker = new ReconciliationWorker(queue, reconciler, informers, registry);
        try {
            worker.start();
            verify(reconciler, timeout(1000)).reconcile(failing);
            verify(reconciler, timeout(500)).reconcile(healthy);
            verify(reconciler, times(1)).reconcile(failing);
        } finally {
            worker.stop();
        }
        // Stopping cancels the timer but preserves the pending retry in the queue.
        assertEquals(failing, queue.poll());
        assertEquals(1,
                registry.get("control_hub.reconciliation.worker.duration").tag("result", "failure").timer().count());
        assertEquals(1,
                registry.get("control_hub.reconciliation.worker.duration").tag("result", "success").timer().count());
        assertEquals(1, registry.get("control_hub.reconciliation.worker.retries").counter().count());
        assertEquals(0, registry.get("control_hub.reconciliation.worker.retries.pending").gauge().value());
        assertEquals(0, registry.get("control_hub.reconciliation.worker.running").gauge().value());
        assertEquals(1, registry.get("control_hub.reconciliation.informers.synced").gauge().value());
    }

    @Test
    void usesAVirtualThreadAndInterruptsItWhileWaitingForWork() throws InterruptedException {
        var informers = mock(KubernetesResourceInformers.class);
        when(informers.hasSynced()).thenReturn(true);
        var reconciler = mock(KubernetesReconciler.class);
        var queue = new ReconciliationQueue(registry);
        var key = new ApplicationKey("production", "api");
        var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var reconciled = new java.util.concurrent.CountDownLatch(1);
        when(reconciler.reconcile(key)).thenAnswer(invocation -> {
            thread.set(Thread.currentThread());
            reconciled.countDown();
            return java.util.Optional.empty();
        });
        queue.enqueue(key);
        var worker = new ReconciliationWorker(queue, reconciler, informers, registry);
        try {
            worker.start();
            assertTrue(reconciled.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(thread.get().isVirtual());
        } finally {
            worker.stop();
        }
        thread.get().join(2000);
        assertFalse(thread.get().isAlive());
        assertFalse(worker.isRunning());
    }

    @Test
    void leavesWorkQueuedUntilCachesSyncThenProcessesIt() {
        var informers = mock(KubernetesResourceInformers.class);
        var reconciler = mock(KubernetesReconciler.class);
        var queue = new ReconciliationQueue(registry);
        var key = new ApplicationKey("production", "api");
        queue.enqueue(key);
        var worker = new ReconciliationWorker(queue, reconciler, informers, registry);
        try {
            worker.start();
            verify(informers, timeout(1000)).hasSynced();
            verify(reconciler, after(100).never()).reconcile(any());
            assertEquals(1, queue.size());
            when(informers.hasSynced()).thenReturn(true);
            verify(reconciler, timeout(2000)).reconcile(key);
        } finally {
            worker.stop();
        }
    }

    @Test
    void stopsWhileWaitingForSyncWithoutConsumingWork() {
        var informers = mock(KubernetesResourceInformers.class);
        var reconciler = mock(KubernetesReconciler.class);
        var queue = new ReconciliationQueue(registry);
        queue.enqueue(new ApplicationKey("production", "api"));
        var worker = new ReconciliationWorker(queue, reconciler, informers, registry);
        try {
            worker.start();
            verify(informers, timeout(1000)).hasSynced();
        } finally {
            worker.stop();
        }
        assertFalse(worker.isRunning());
        assertEquals(1, queue.size());
        verifyNoInteractions(reconciler);
    }

    @Test
    void failedReconciliationIsRetriedWithoutATightLoop() {
        var informers = mock(KubernetesResourceInformers.class);
        when(informers.hasSynced()).thenReturn(true);
        var reconciler = mock(KubernetesReconciler.class);
        var queue = new ReconciliationQueue(registry);
        var key = new ApplicationKey("production", "api");
        queue.enqueue(key);
        when(reconciler.reconcile(key)).thenThrow(new IllegalStateException("Database unavailable"));
        var worker = new ReconciliationWorker(queue, reconciler, informers, registry);
        try {
            worker.start();
            verify(reconciler, timeout(1000)).reconcile(key);
            verify(reconciler, after(200).times(1)).reconcile(key);
            verify(reconciler, timeout(2000).times(2)).reconcile(key);
        } finally {
            worker.stop();
        }
    }
}
