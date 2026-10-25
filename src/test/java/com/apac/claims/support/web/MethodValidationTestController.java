package com.apac.claims.support.web;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.Min;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only: constraints on a parameter without {@code @Validated}, which Spring reports as a method validation error. */
@RestController
@Hidden
@Profile("error-contract-test")
@RequestMapping("/api/v1/_test/method-validation")
public class MethodValidationTestController {

    @GetMapping
    public String check(@RequestParam("n") @Min(1) int n) {
        return "n " + n;
    }
}
