package com.golias.control_hub.domain.operation.action;

import com.golias.control_hub.domain.application.entities.Application;
import com.golias.control_hub.domain.application.exceptions.ApplicationException;
import com.golias.control_hub.domain.application.port.ApplicationPort;
import com.golias.control_hub.domain.kubernetes.exceptions.KubernetesException;
import com.golias.control_hub.domain.kubernetes.ports.KubernetesOperations;
import com.golias.control_hub.domain.operation.entities.Operation;
import com.golias.control_hub.domain.operation.repository.OperationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;

@Service
@Slf4j
public class RestartApp {

    private final ApplicationPort applicationPort;

    private final KubernetesOperations kubernetesOperations;

    private final OperationRepository operationRepository;

    public RestartApp(ApplicationPort applicationPort, KubernetesOperations kubernetesOperations, OperationRepository operationRepository) {
        this.applicationPort = applicationPort;
        this.kubernetesOperations = kubernetesOperations;
        this.operationRepository = operationRepository;
    }

    public void restart(String namespace, String name) {
        Operation savedOp = null;
        try {
            Application app = applicationPort.findApplication(namespace, name);
            Operation operation = Operation.builder()
                    .applicationId(app.getId())
                    .type("Restart")
                    .status("Pending")
                    .requestedAt(ZonedDateTime.now())
                    .startedAt(ZonedDateTime.now())
                    .build();
            savedOp = operationRepository.save(operation);
            kubernetesOperations.restart(app.getNamespace(), app.getName());
            operationRepository.save(savedOp.toBuilder().completedAt(ZonedDateTime.now()).status("Completed").build());
        } catch (ApplicationException e) {
            log.error("Error fetching the application requested", e);
            if (savedOp != null) {
                operationRepository.save(savedOp.toBuilder().error(e.getMessage()).status("ERROR").build());
            }
            throw e;
        }
        catch (KubernetesException e) {
            log.error("Error restarting the application", e);
            if (savedOp != null) {
                operationRepository.save(savedOp.toBuilder().error(e.getMessage()).status("ERROR").build());
            }
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error found", e);
            if (savedOp != null) {
                operationRepository.save(savedOp.toBuilder().error(e.getMessage()).status("ERROR").build());
            }
            throw e;
        }
    }

}
