package com.golias.control_hub.domain.application.repository;

import com.golias.control_hub.domain.application.entities.ApplicationStatusEvent;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApplicationStatusEventRepo extends ListCrudRepository<ApplicationStatusEvent, UUID> {

    Optional<ApplicationStatusEvent> findFirstByApplicationIdOrderByOccurredAtDesc(UUID applicationId);

    List<ApplicationStatusEvent> findByApplicationIdOrderByOccurredAtDesc(UUID applicationId, Limit limit);

    @Modifying
    @Query("delete from ApplicationStatusEvent event where event.occurredAt < :cutoff")
    int deleteOccurredBefore(@Param("cutoff") ZonedDateTime cutoff);
}
