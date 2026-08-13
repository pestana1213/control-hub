package com.golias.control_hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ControlHubApplication {

	public static void main(String[] args) {
		SpringApplication.run(ControlHubApplication.class, args);
	}

}
