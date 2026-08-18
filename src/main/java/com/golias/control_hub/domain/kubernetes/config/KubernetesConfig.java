package com.golias.control_hub.domain.kubernetes.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class KubernetesConfig {

    @Bean
    public ApiClient apiClient() throws IOException {
        // Auto-detects: in-cluster config if running inside k8s,
        // otherwise falls back to ~/.kube/config
        ApiClient client = Config.defaultClient();
        client.setReadTimeout(0); // avoid timeouts on watch/exec calls, optional
        return client;
    }
}
