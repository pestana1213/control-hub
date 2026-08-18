package com.golias.control_hub.domain.kubernetes.exceptions;

public class KubernetesException extends RuntimeException {
    public KubernetesException(String message) {
        super(message);
    }
    public KubernetesException(String message, Throwable e) {
        super(message, e);
    }
}
