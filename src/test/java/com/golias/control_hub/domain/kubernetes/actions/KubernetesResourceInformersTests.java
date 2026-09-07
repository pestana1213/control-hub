package com.golias.control_hub.domain.kubernetes.actions;

import com.golias.control_hub.domain.kubernetes.dto.ApplicationKey;
import com.golias.control_hub.domain.kubernetes.state.ReconciliationQueue;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

class KubernetesResourceInformersTests {
    private final ReconciliationQueue queue = mock(ReconciliationQueue.class);
    private final Map<Class<?>, ResourceEventHandler<KubernetesObject>> handlers = new HashMap<>();

    @BeforeEach
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void setUp() {
        var factory = mock(SharedInformerFactory.class);
        when(factory.sharedIndexInformerFor(any(), any(), any(), anyLong())).thenAnswer(invocation -> {
            var informer = mock(SharedIndexInformer.class);
            doAnswer(registration -> {
                handlers.put(invocation.getArgument(1), registration.getArgument(0));
                return null;
            }).when(informer).addEventHandler(any());
            return informer;
        });
        new KubernetesResourceInformers(new ApiClient(), factory, queue);
    }

    @Test
    void allResourceTypesEnqueueAddsAndDeletesIncludingUnknownFinalState() {
        var metadata = metadata("api", "1");
        for (KubernetesObject resource : new KubernetesObject[] { new V1Deployment().metadata(metadata),
                new V1Pod().metadata(metadata), new V1Service().metadata(metadata),
                new V1Ingress().metadata(metadata) }) {
            var handler = handlers.get(resource.getClass());
            handler.onAdd(resource);
            handler.onDelete(resource, false);
            handler.onDelete(resource, true);
        }
        verify(queue, times(12)).enqueue(new ApplicationKey("production", "api"));
    }

    @Test
    void labelChangesReconcileBothApplications() {
        handlers.get(V1Pod.class).onUpdate(new V1Pod().metadata(metadata("old", "1")),
                new V1Pod().metadata(metadata("new", "2")));
        verify(queue).enqueue(new ApplicationKey("production", "old"));
        verify(queue).enqueue(new ApplicationKey("production", "new"));
        verifyNoMoreInteractions(queue);
    }

    @Test
    void removingLabelReconcilesPreviousApplication() {
        handlers.get(V1Pod.class).onUpdate(new V1Pod().metadata(metadata("api", "1")),
                new V1Pod().metadata(new V1ObjectMeta().namespace("production").resourceVersion("2")));
        verify(queue).enqueue(new ApplicationKey("production", "api"));
        verifyNoMoreInteractions(queue);
    }

    @Test
    void updatesAndResyncsEnqueueTheApplicationOncePerCallback() {
        var handler = handlers.get(V1Pod.class);
        handler.onUpdate(new V1Pod().metadata(metadata("api", "1")), new V1Pod().metadata(metadata("api", "2")));
        var current = new V1Pod().metadata(metadata("api", "2"));
        handler.onUpdate(current, current);
        verify(queue, times(2)).enqueue(new ApplicationKey("production", "api"));
        verifyNoMoreInteractions(queue);
    }

    @Test
    void ignoresResourcesWithoutValidApplicationIdentity() {
        var handler = handlers.get(V1Pod.class);
        handler.onAdd(new V1Pod());
        handler.onAdd(new V1Pod().metadata(new V1ObjectMeta().namespace("production")));
        handler.onAdd(new V1Pod().metadata(metadata("", "1")));
        handler.onAdd(new V1Pod().metadata(metadata("api", "1").namespace(null)));
        handler.onAdd(new V1Pod().metadata(metadata("api", "1").namespace(" ")));
        verifyNoInteractions(queue);
    }

    private V1ObjectMeta metadata(String name, String version) {
        return new V1ObjectMeta().namespace("production").resourceVersion(version)
                .labels(Map.of("app.kubernetes.io/name", name));
    }
}
