package com.golias.control_hub.domain.application.utils;

import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import com.golias.control_hub.domain.application.entities.Application;
import org.springframework.stereotype.Component;

@Component
public class Converter implements Converters {

    public ApplicationDTO convert(Application application) {
        return new ApplicationDTO(application.name());
    }
}
