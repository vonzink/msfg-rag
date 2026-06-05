package com.msfg.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MsfgRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsfgRagApplication.class, args);
    }
}
