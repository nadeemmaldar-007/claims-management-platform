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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Answers an authenticated request that is not allowed with a JSON 403 in the common error shape. Failures raised
 * inside controllers by {@code @PreAuthorize} are handled by the global exception handler; this handles the ones
 * raised by the security filters.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RestAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        log.debug("Access denied to {}", request.getRequestURI());
        ProblemDetail problem = ProblemDetails.of(clock, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED,
                "Access denied");
        ProblemDetails.withInstance(problem, request.getRequestURI());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
