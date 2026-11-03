package com.golias.control_hub.domain.application.entrypoint;

import com.golias.control_hub.domain.application.action.ApplicationDesiredStateService;
import com.golias.control_hub.domain.application.action.GetApplicationDiff;
import com.golias.control_hub.domain.application.dto.ApplicationDiffDTO;
import com.golias.control_hub.domain.application.dto.DesiredSpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/applications/{id}")
public class ApplicationDesiredStateController {

    private final ApplicationDesiredStateService desiredState;
    private final GetApplicationDiff diff;

    public ApplicationDesiredStateController(ApplicationDesiredStateService desiredState, GetApplicationDiff diff) {
        this.desiredState = desiredState;
        this.diff = diff;
    }

    @PutMapping("/desired-state")
    public DesiredSpec setDesiredState(@PathVariable UUID id, @RequestBody DesiredSpec request) {
        return desiredState.set(id, request);
    }

    @GetMapping("/desired-state")
    public DesiredSpec getDesiredState(@PathVariable UUID id) {
        return desiredState.get(id);
    }

    @DeleteMapping("/desired-state")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearDesiredState(@PathVariable UUID id) {
        desiredState.clear(id);
    }

    /** Reports drift between the declared spec and the cluster. Nothing is reconciled. */
    @GetMapping("/diff")
    public ApplicationDiffDTO getDiff(@PathVariable UUID id) {
        return diff.getDiff(id);
    }
}
