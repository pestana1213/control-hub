package com.golias.control_hub.domain.kubernetes.dto;

import java.util.Objects;

public record ApplicationKey(String namespace, String name) {
    public ApplicationKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
    }
}
