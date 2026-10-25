package com.apac.claims.shared.api;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.apac.claims.shared.exception.ApiException;
import com.apac.claims.shared.exception.ErrorCode;
import com.apac.claims.shared.exception.ValidationFailedException;
import com.apac.claims.shared.exception.ValidationFailedException.FieldError;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.ObjectError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The one place where exceptions become HTTP responses, all in the RFC 7807 shape with {@code code}, {@code timestamp}
 * and {@code correlationId}. Feature modules only throw {@link ApiException} subclasses, so this class never needs to
 * know about them.
 *
 * <p>{@link AccessDeniedException} and {@link AuthenticationException} are handled explicitly on purpose: the
 * catch-all at the bottom would otherwise turn a failed {@code @PreAuthorize} into a 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    // ---- business exceptions ------------------------------------------------------------------------------

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        if (ex.getStatus().is5xxServerError()) {
            // Anticipated failures such as an unavailable payment gateway: worth a warning, not a stack trace.
            log.warn("Request failed: status={} code={} message={}", ex.getStatus().value(), ex.getErrorCode(),
                    ex.getMessage());
        } else {
            log.debug("Request rejected: status={} code={}", ex.getStatus().value(), ex.getErrorCode());
        }
        List<FieldError> errors = ex instanceof ValidationFailedException validation ? validation.getErrors() : null;
        return problem(ex, ex.getStatus(), ex.getErrorCode(), ex.getMessage(), errors, request);
    }

    // ---- security -----------------------------------------------------------------------------------------

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        log.debug("Access denied: {}", ex.getClass().getSimpleName());
        return problem(ex, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "Access denied", null, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        log.debug("Authentication failed: {}", ex.getClass().getSimpleName());
        return problem(ex, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Authentication required", null,
                request);
    }

    // ---- validation and malformed requests ----------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.add(new FieldError(error.getField(), messageOf(error.getDefaultMessage()))));
        for (ObjectError error : ex.getBindingResult().getGlobalErrors()) {
            errors.add(new FieldError(error.getObjectName(), messageOf(error.getDefaultMessage())));
        }
        return problem(ex, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                sorted(errors), request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error ->
                    errors.add(new FieldError(name == null ? "parameter" : name, messageOf(error.getDefaultMessage()))));
        });
        return problem(ex, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                sorted(errors), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.add(new FieldError(lastNode(violation.getPropertyPath()), messageOf(violation.getMessage())));
        }
        return problem(ex, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                sorted(errors), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        // The body is never echoed: it can contain passwords. Only the name of the offending field is reported.
        String detail = "Malformed request body";
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String path = mismatch.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."));
            detail = "Invalid value for field '" + path + "'";
        }
        return problem(ex, HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, detail, null, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        List<FieldError> errors = List.of(new FieldError(ex.getParameterName(), "is required"));
        return problem(ex, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Required parameter '" + ex.getParameterName() + "' is missing", errors, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex, WebRequest request) {
        Class<?> required = ex.getRequiredType();
        String message = required != null && required.isEnum()
                ? "must be one of " + Arrays.stream(required.getEnumConstants()).map(String::valueOf)
                        .collect(Collectors.joining(", "))
                : "has an invalid value";
        return problem(ex, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Parameter '" + ex.getName() + "' has an invalid value", List.of(new FieldError(ex.getName(), message)),
                request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
                                                                    HttpStatusCode status, WebRequest request) {
        return problem(ex, HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Resource not found", null, request);
    }

    // ---- persistence --------------------------------------------------------------------------------------

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                       WebRequest request) {
        log.debug("Optimistic lock failure on {}", ex.getPersistentClassName());
        return problem(ex, HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was changed by another request; reload it and try again", null, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        // The message of a data integrity exception contains SQL and values, so only the type is logged.
        log.warn("Data integrity violation: {}", ex.getClass().getSimpleName());
        return problem(ex, HttpStatus.CONFLICT, ErrorCode.DUPLICATE_RESOURCE,
                "The request conflicts with existing data", null, request);
    }

    // ---- everything else ----------------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        // The correlation id is in the logging context, so this line can be matched to the client's error body.
        log.error("Unhandled exception", ex);
        return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Unexpected error", null,
                request);
    }

    /**
     * Every response that Spring MVC or this class produces passes through here, so this is where the extra
     * properties are added. For exceptions that carry their own body (405, 415 and similar) the body is created
     * first, because the parent only creates it after this method returns.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        Object resolved = body;
        if (resolved == null && ex instanceof ErrorResponse errorResponse) {
            resolved = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (resolved instanceof ProblemDetail problem) {
            ProblemDetails.decorate(clock, problem, ProblemDetails.defaultCodeFor(statusCode));
        }
        return super.handleExceptionInternal(ex, resolved, headers, statusCode, request);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private ResponseEntity<Object> problem(Exception ex, HttpStatusCode status, ErrorCode code, String detail,
                                           @Nullable List<FieldError> errors, WebRequest request) {
        ProblemDetail problem = ProblemDetails.of(clock, status, code, detail, errors);
        if (request instanceof ServletWebRequest servletRequest) {
            ProblemDetails.withInstance(problem, servletRequest.getRequest().getRequestURI());
        }
        return handleExceptionInternal(ex, problem, new HttpHeaders(), status, request);
    }

    private static List<FieldError> sorted(List<FieldError> errors) {
        return errors.stream()
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
                .toList();
    }

    private static String messageOf(@Nullable String message) {
        return message == null ? "is invalid" : message;
    }

    private static String lastNode(Path path) {
        String last = "";
        for (Path.Node node : path) {
            last = node.getName() == null ? last : node.getName();
        }
        return last;
    }
}
