package com.golias.control_hub.domain.application.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder;
import org.jspecify.annotations.NonNull;

import java.time.ZonedDateTime;
import java.util.UUID;

@Builder(toBuilder = true)
@Entity
@Table(name = "applications")
public record Application(
        @Id
        UUID id,
        @NonNull String name,
        @NonNull String namespace,
        String description,
        String repositoryUrl,
        String argocdApplication,
        @NonNull ZonedDateTime createdAt,
        @NonNull ZonedDateTime updatedAt
) {
}
