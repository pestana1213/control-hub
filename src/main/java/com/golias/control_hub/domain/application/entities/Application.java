package com.golias.control_hub.domain.application.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Id;
import lombok.*;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder(toBuilder = true)
public class Application {

        @Id
        private UUID id;

        private String name;

        private String namespace;

        private String description;

        private String repositoryUrl;

        private String argocdApplication;

        @Column(name = "created_at", updatable = false)
        private ZonedDateTime createdAt;

        @Column(name = "updated_at")
        private ZonedDateTime updatedAt;

        @PrePersist
        public void prePersist() {
                if (this.id == null) {
                        this.id = generateId(this.name, this.namespace);
                }
                this.createdAt = ZonedDateTime.now();
                this.updatedAt = this.createdAt;
        }

        @PreUpdate
        public void preUpdate() {
                this.updatedAt = ZonedDateTime.now();
        }

        private static UUID generateId(String name, String namespace) {
                return UUID.nameUUIDFromBytes((namespace + ":" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
}