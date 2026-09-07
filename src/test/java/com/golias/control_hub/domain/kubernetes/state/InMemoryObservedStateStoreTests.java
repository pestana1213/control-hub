package com.golias.control_hub.domain.kubernetes.state;

import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryObservedStateStoreTests {
    @Test
    void storesAndRemovesStateByApplicationKey() {
        var store = new InMemoryObservedStateStore();
        var key = new ApplicationKey("production", "api");
        var state = new ObservedApplicationState(List.of(), List.of(), List.of(), List.of(), Instant.now());
        assertTrue(store.get(key).isEmpty());
        store.put(key, state);
        assertEquals(state, store.get(key).orElseThrow());
        store.remove(key);
        assertTrue(store.get(key).isEmpty());
    }
}
