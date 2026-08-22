package com.golias.control_hub.domain.operation.entrypoint;

import com.golias.control_hub.domain.operation.action.RestartApp;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("v1/operations/restart")
public class Restart {

    private final RestartApp restartApp;

    public Restart(RestartApp restartApp) {
        this.restartApp = restartApp;
    }

    @PostMapping
    public void restartApplication(@RequestParam String namespace, @RequestParam String application) {
        restartApp.restart(namespace, application);
    }
}
