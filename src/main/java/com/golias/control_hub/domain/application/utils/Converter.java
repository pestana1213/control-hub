package com.golias.control_hub.domain.application.utils;

import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import com.golias.control_hub.domain.application.entities.Application;
import org.springframework.stereotype.Component;

@Component
public class Converter implements Converters {

    public ApplicationDTO convert(Application application) {
        return ApplicationDTO.builder()
                .name(application.getName())
                .namespace(application.getNamespace())
                .description(application.getDescription())
                .repositoryUrl(application.getRepositoryUrl())
                .url(application.getIngress())
            .build();
    }
}
