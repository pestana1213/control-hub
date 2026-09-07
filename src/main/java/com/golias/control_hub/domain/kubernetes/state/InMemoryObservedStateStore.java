package com.golias.control_hub.domain.kubernetes.state;

import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesObservedState;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryObservedStateStore implements KubernetesObservedState {

    private final ConcurrentMap<ApplicationKey, ObservedApplicationState> states = new ConcurrentHashMap<>();

    @Override
    public Optional<ObservedApplicationState> get(ApplicationKey key) {
        return Optional.ofNullable(states.get(key));
    }

    @Override
    public void put(ApplicationKey key, ObservedApplicationState state) {
        states.put(key, state);
    }

    @Override
    public void remove(ApplicationKey key) {
        states.remove(key);
    }
}
