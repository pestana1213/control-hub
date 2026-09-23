package com.golias.control_hub.domain.kubernetes.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import okhttp3.Request;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class KubernetesConfig {

    private static final String DEPLOYMENT_PATH_PREFIX = "/apis/apps/v1/namespaces/";
    private static final String STRATEGIC_MERGE_PATCH = "application/strategic-merge-patch+json";

    @Bean
    public ApiClient apiClient() throws IOException {
        // Auto-detects: in-cluster config if running inside k8s,
        // otherwise falls back to ~/.kube/config
        ApiClient client = Config.defaultClient();
        client.setReadTimeout(0); // avoid timeouts on watch/exec calls, optional
        client.setHttpClient(client.getHttpClient().newBuilder().addInterceptor(chain -> {
            Request request = chain.request();
            if (isDeploymentPatch(request)) {
                request = request.newBuilder().header("Content-Type", STRATEGIC_MERGE_PATCH).build();
            }
            return chain.proceed(request);
        }).build());
        return client;
    }

    private static boolean isDeploymentPatch(Request request) {
        String path = request.url().encodedPath();
        return request.method().equals("PATCH") && path.startsWith(DEPLOYMENT_PATH_PREFIX)
                && path.matches(".*/deployments/[^/]+$");
    }
}
