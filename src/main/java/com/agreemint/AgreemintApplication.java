package com.agreemint;

import com.agreemint.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(StorageProperties.class)
public class AgreemintApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgreemintApplication.class, args);
    }
}
