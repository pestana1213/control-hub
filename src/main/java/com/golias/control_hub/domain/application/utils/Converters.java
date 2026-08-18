package com.golias.control_hub.domain.application.utils;

import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import com.golias.control_hub.domain.application.dto.IngressDTO;
import com.golias.control_hub.domain.application.dto.PodDTO;
import com.golias.control_hub.domain.application.entities.Application;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1Pod;

public interface Converters{
    ApplicationDTO convert(Application application);
    IngressDTO toIngressDTO(V1Ingress ingress);
    PodDTO toPodDTO(V1Pod pod);
}
