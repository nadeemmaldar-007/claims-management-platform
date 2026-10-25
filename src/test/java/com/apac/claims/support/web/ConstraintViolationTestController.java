package com.apac.claims.support.web;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.Min;

import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only. {@code @Validated} on the class makes Spring validate through an AOP proxy, which raises a
 * {@code ConstraintViolationException}; the sibling controller without it raises
 * {@code HandlerMethodValidationException}. Both must become a 400.
 */
@RestController
@Validated
@Hidden
@Profile("error-contract-test")
@RequestMapping("/api/v1/_test/constraint")
public class ConstraintViolationTestController {

    @GetMapping
    public String check(@RequestParam("n") @Min(1) int n) {
        return "n " + n;
    }
}
