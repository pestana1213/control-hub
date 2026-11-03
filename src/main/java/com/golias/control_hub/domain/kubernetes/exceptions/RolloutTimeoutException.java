package com.golias.control_hub.domain.kubernetes.exceptions;

/**
 * Raised when a patched Deployment does not reach its desired revision in time. Carries the
 * observed reasons so the operation record explains why the rollout never completed.
 */
public class RolloutTimeoutException extends RuntimeException {
    public RolloutTimeoutException(String message) {
        super(message);
    }

    public RolloutTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
