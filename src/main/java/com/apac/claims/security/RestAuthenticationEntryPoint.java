package com.apac.claims.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

import com.apac.claims.shared.api.ProblemDetails;
import com.apac.claims.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Answers an unauthenticated request with a JSON 401 in the common error shape. The body is the same whether the
 * token is missing, malformed, tampered with or expired, so it does not help anyone probing for valid tokens.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        log.debug("Unauthenticated request to {}: {}", request.getRequestURI(), exception.getClass().getSimpleName());
        ProblemDetail problem = ProblemDetails.of(clock, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED,
                "Authentication required");
        ProblemDetails.withInstance(problem, request.getRequestURI());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
