package com.legalgate.intake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IntakeOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntakeOrchestratorApplication.class, args);
    }
}
