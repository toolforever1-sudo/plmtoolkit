package com.sandisk.plm.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
public class Application {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "app.scheduling.disabled", havingValue = "false", matchIfMissing = true)
    static class SchedulingConfig {}

    public static void main(String[] args) {
        System.out.println("=== PLM Toolkit v1.0.1-proxy (Agile via microservice) ===");
        SpringApplication.run(Application.class, args);
    }
}
