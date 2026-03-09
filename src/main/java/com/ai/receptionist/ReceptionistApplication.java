package com.ai.receptionist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.ai.receptionist")
@EnableRetry
@EnableScheduling
public class ReceptionistApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReceptionistApplication.class, args);
    }
}
