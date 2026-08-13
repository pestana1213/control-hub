package com.golias.control_hub.domain.application.repository;

import com.golias.control_hub.domain.application.entities.Application;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApplicationRepo extends ListCrudRepository<Application, UUID> {

    @Query("select a from Application a where a.name = :name and a.namespace = :namespace")
    Optional<Application> findByNameAndNameSpace(@Param("name") String name, @Param("namespace") String namespace);
}
