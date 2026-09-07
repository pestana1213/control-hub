package com.golias.control_hub.domain.kubernetes.ports;

import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.dto.ObservedApplicationState;

import java.util.Optional;

public interface KubernetesObservedState {

    Optional<ObservedApplicationState> get(ApplicationKey key);

    void put(ApplicationKey key, ObservedApplicationState state);

    void remove(ApplicationKey key);
}
