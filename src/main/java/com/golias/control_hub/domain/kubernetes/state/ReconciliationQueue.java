package com.golias.control_hub.domain.kubernetes.state;

import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class ReconciliationQueue {

    private static final int CAPACITY = 1_000;

    private final BlockingQueue<ApplicationKey> queue = new LinkedBlockingQueue<>(CAPACITY);

    private final Set<ApplicationKey> pending = ConcurrentHashMap.newKeySet();

    private final Counter accepted;
    private final Counter duplicate;
    private final Counter rejected;

    public ReconciliationQueue(MeterRegistry registry) {
        registry.gauge("control_hub.reconciliation.queue.size", this, ReconciliationQueue::size);
        accepted = registry.counter("control_hub.reconciliation.queue.enqueue", "result", "accepted");
        duplicate = registry.counter("control_hub.reconciliation.queue.enqueue", "result", "duplicate");
        rejected = registry.counter("control_hub.reconciliation.queue.enqueue", "result", "full");
    }

    public boolean enqueue(ApplicationKey key) {
        if (!pending.add(key)) {
            duplicate.increment();
            return false; // already in the queue
        }

        if (!queue.offer(key)) {
            pending.remove(key);
            rejected.increment();
            return false;
        }

        accepted.increment();
        return true;
    }

    public ApplicationKey poll() {
        return queue.poll();
    }

    public ApplicationKey take() throws InterruptedException {
        return queue.take();
    }

    public void completed(ApplicationKey key) {
        pending.remove(key);
    }

    public int size() {
        return queue.size();
    }

    public void markAsProcessing(ApplicationKey key) {
        pending.remove(key);
    }

    public void retry(ApplicationKey key) {
        enqueue(key);
    }
}
