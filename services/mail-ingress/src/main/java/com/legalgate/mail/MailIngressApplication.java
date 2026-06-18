package com.legalgate.mail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MailIngressApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailIngressApplication.class, args);
    }
}
