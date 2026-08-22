package com.golias.control_hub.domain.application.port;

import com.golias.control_hub.domain.application.entities.Application;

public interface ApplicationPort {

    Application findApplication(String namespace, String name);
}
