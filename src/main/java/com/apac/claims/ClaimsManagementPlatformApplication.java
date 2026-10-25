package com.apac.claims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the claims platform: one deployable made of the modules under {@code com.apac.claims}.
 * Configuration records are discovered by scanning, so modules never register their own properties by hand.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ClaimsManagementPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimsManagementPlatformApplication.class, args);
    }
}
