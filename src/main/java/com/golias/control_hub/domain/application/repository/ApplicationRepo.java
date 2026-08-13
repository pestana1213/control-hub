package com.golias.control_hub.domain.application.repository;

import com.golias.control_hub.domain.application.entities.Application;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ApplicationRepo extends JpaRepository<Application, UUID> {

}
