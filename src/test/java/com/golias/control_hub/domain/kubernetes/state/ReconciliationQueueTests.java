package com.golias.control_hub.domain.kubernetes.state;

import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationQueueTests {
    @Test
    void measuresQueueDepthDuplicatesAndCapacityRejections() {
        var registry = new SimpleMeterRegistry();
        try {
            var queue = new ReconciliationQueue(registry);
            var key = new ApplicationKey("production", "api");
            assertTrue(queue.enqueue(key));
            assertFalse(queue.enqueue(key));
            for (int i = 1; i < 1000; i++) {
                assertTrue(queue.enqueue(new ApplicationKey("production", "app-" + i)));
            }
            var overflow = new ApplicationKey("production", "overflow");
            assertFalse(queue.enqueue(overflow));
            assertEquals(1000, registry.get("control_hub.reconciliation.queue.size").gauge().value());
            assertEquals(1000, registry.get("control_hub.reconciliation.queue.enqueue").tag("result", "accepted")
                    .counter().count());
            assertEquals(1, registry.get("control_hub.reconciliation.queue.enqueue").tag("result", "duplicate")
                    .counter().count());
            assertEquals(1,
                    registry.get("control_hub.reconciliation.queue.enqueue").tag("result", "full").counter().count());
            queue.markAsProcessing(queue.poll());
            assertEquals(999, registry.get("control_hub.reconciliation.queue.size").gauge().value());
            assertTrue(queue.enqueue(overflow));
        } finally {
            registry.close();
        }
    }
}
