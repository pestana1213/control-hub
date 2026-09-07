package com.golias.control_hub.domain.operation.repository;

import com.golias.control_hub.domain.operation.entities.Operation;
import org.springframework.data.repository.ListCrudRepository;

import java.util.UUID;
import com.golias.control_hub.domain.operation.entities.OperationStatus;

public interface OperationRepository extends ListCrudRepository<Operation, UUID> {

    boolean existsByApplicationIdAndStatusIn(UUID applicationId, Iterable<OperationStatus> statuses);
}
