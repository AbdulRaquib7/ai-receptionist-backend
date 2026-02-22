package com.ai.receptionist.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.ai.receptionist")
public class ReceptionistApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReceptionistApplication.class, args);
    }
}
