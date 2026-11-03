package com.golias.control_hub.domain.application.repository;

import com.golias.control_hub.domain.application.entities.ApplicationDesiredState;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ApplicationDesiredStateRepo extends ListCrudRepository<ApplicationDesiredState, UUID> {
}
