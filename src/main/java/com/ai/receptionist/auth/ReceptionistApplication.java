package com.ai.receptionist.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.ai.receptionist")
@EntityScan("com.ai.receptionist.entity")
@EnableJpaRepositories("com.ai.receptionist.repository")
public class ReceptionistApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReceptionistApplication.class, args);
    }
}
