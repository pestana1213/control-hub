package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.state.ReconciliationQueue;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ReconciliationWorker implements SmartLifecycle {

    private static final long SYNC_POLL_MILLIS = 250;
    private static final long RETRY_DELAY_MILLIS = 1000;

    private final KubernetesResourceInformers informers;
    private final ReconciliationQueue queue;
    private final KubernetesReconciler reconciler;

    private final MeterRegistry registry;
    private final Timer successfulAttempts;
    private final Timer failedAttempts;
    private final Counter retries;

    private Thread workerThread;
    private ScheduledExecutorService scheduler;
    private final Set<ApplicationKey> scheduledRetries = new HashSet<>();

    private volatile boolean running;

    public ReconciliationWorker(ReconciliationQueue queue, KubernetesReconciler reconciler,
            KubernetesResourceInformers informers, MeterRegistry registry) {
        this.registry = registry;
        successfulAttempts = registry.timer("control_hub.reconciliation.worker.duration", "result", "success");
        failedAttempts = registry.timer("control_hub.reconciliation.worker.duration", "result", "failure");
        retries = registry.counter("control_hub.reconciliation.worker.retries");
        registry.gauge("control_hub.reconciliation.worker.running", this, worker -> worker.isRunning() ? 1 : 0);
        registry.gauge("control_hub.reconciliation.worker.retries.pending", this, ReconciliationWorker::pendingRetries);
        registry.gauge("control_hub.reconciliation.informers.synced", informers, value -> value.hasSynced() ? 1 : 0);
        this.informers = informers;
        this.queue = queue;
        this.reconciler = reconciler;
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("kubernetes-reconciliation-scheduler").factory());
        scheduler.execute(this::startWhenSynced);
    }

    @Override
    @PreDestroy
    public synchronized void stop() {
        if (!isRunning()) {
            return;
        }
        running = false;
        scheduler.shutdownNow();
        if (workerThread != null) {
            workerThread.interrupt();
        }
        scheduledRetries.forEach(queue::retry);
        scheduledRetries.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private synchronized void startWhenSynced() {
        if (!running) {
            return;
        }
        if (!informers.hasSynced()) {
            scheduler.schedule(this::startWhenSynced, SYNC_POLL_MILLIS, TimeUnit.MILLISECONDS);
            return;
        }
        workerThread = Thread.ofVirtual().name("kubernetes-reconciliation").start(this::work);
    }

    private synchronized void scheduleRetry(ApplicationKey key) {
        if (!running) {
            queue.retry(key);
            return;
        }
        if (scheduledRetries.add(key)) {
            retries.increment();
            scheduler.schedule(() -> retry(key), RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void retry(ApplicationKey key) {
        if (running && scheduledRetries.remove(key)) {
            queue.retry(key);
        }
    }

    private synchronized int pendingRetries() {
        return scheduledRetries.size();
    }

    private void work() {
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                ApplicationKey key = queue.take();
                queue.markAsProcessing(key);
                Timer.Sample sample = Timer.start(registry);
                try {
                    reconciler.reconcile(key);
                    sample.stop(successfulAttempts);
                } catch (Exception exception) {
                    sample.stop(failedAttempts);
                    if (Thread.currentThread().isInterrupted()) {
                        queue.retry(key);
                        break;
                    }
                    log.error("Failed to reconcile {}", key, exception);
                    scheduleRetry(key);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
