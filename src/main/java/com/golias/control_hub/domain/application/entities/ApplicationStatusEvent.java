package com.golias.control_hub.domain.application.entities;

import com.golias.control_hub.domain.application.enums.ApplicationStatus;
import com.golias.control_hub.domain.application.enums.ApplicationStatusReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One observed health transition. Only transitions are stored, not every Kubernetes event, so the
 * history stays short enough to answer "what happened?" without turning PostgreSQL into a log
 * store.
 */
@Entity
@Table(name = "application_status_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder(toBuilder = true)
public class ApplicationStatusEvent {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    /** Null for the first transition recorded for an application. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private ApplicationStatus toStatus;

    /** The most severe reason behind the new status, or null when the application became healthy. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code")
    private ApplicationStatusReason reasonCode;

    private String message;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private ZonedDateTime occurredAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.occurredAt == null) {
            this.occurredAt = ZonedDateTime.now();
        }
    }
}
