package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.repository.ApplicationStatusEventRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZonedDateTime;

/** Keeps the transition history short; it answers "what happened?", it is not a log store. */
@Component
@Slf4j
public class ApplicationStatusEventRetention {

    private final ApplicationStatusEventRepo repository;
    private final Duration retention;

    public ApplicationStatusEventRetention(ApplicationStatusEventRepo repository,
            @Value("${control.hub.applications.status-events.retention:14d}") Duration retention) {
        this.repository = repository;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${control.hub.applications.status-events.purge-interval:1h}",
            initialDelayString = "${control.hub.applications.status-events.purge-interval:1h}")
    @Transactional
    public void purge() {
        int removed = repository.deleteOccurredBefore(ZonedDateTime.now().minus(retention));
        if (removed > 0) {
            log.info("Removed {} application status events older than {}", removed, retention);
        }
    }
}
