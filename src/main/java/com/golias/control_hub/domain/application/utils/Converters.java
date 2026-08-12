package com.golias.control_hub.domain.application.utils;

import com.golias.control_hub.domain.application.dto.ApplicationDTO;
import com.golias.control_hub.domain.application.entities.Application;

public interface Converters{
    ApplicationDTO convert(Application application);
}
