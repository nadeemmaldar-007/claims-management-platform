package com.apac.claims.bootstrap.api;

import java.net.URI;

import io.swagger.v3.oas.annotations.Hidden;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sends a browser that opens the bare address to Swagger UI. It is hidden from the OpenAPI document. */
@RestController
@Hidden
public class RootRedirectController {

    private static final URI SWAGGER_UI = URI.create("/swagger-ui/index.html");

    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.FOUND).location(SWAGGER_UI).build();
    }
}
