package com.golias.control_hub.domain.application.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * What an application is supposed to look like. A null field is not managed, so drift is only
 * reported for what was explicitly declared.
 */
@Entity
@Table(name = "application_desired_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder(toBuilder = true)
public class ApplicationDesiredState {

    @Id
    @Column(name = "application_id")
    private UUID applicationId;

    private String image;

    private Integer replicas;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void touch() {
        this.updatedAt = ZonedDateTime.now();
    }
}
