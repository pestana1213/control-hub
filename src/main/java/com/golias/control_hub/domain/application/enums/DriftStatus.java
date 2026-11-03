package com.golias.control_hub.domain.application.enums;

public enum DriftStatus {
    /** Every managed field matches what is running. */
    IN_SYNC,
    /** At least one managed field differs; see the drift list. */
    DRIFTED,
    /** No desired state has been declared, so there is nothing to compare against. */
    UNMANAGED,
    /** A desired state exists but the cluster state is unavailable, so drift is unknown. */
    UNOBSERVED
}
