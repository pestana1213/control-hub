package com.golias.control_hub.domain.application.enums;

public enum ApplicationStatusReason {
    POD_CRASH_LOOP, REPLICA_NOT_READY, IMAGE_PULL_ERROR, ROLLOUT_IN_PROGRESS, PROGRESS_DEADLINE_EXCEEDED,
    REPLICA_FAILURE, OBSERVED_STATE_UNAVAILABLE
}
