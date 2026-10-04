package com.rammendez.warehouse.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.MDC;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ProblemDetail business(BusinessException error, HttpServletRequest request) {
        return problem(error.status(), error.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "Permission or warehouse scope denied", request);
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    ProblemDetail invalid(Exception error, HttpServletRequest request) {
        String detail = "Invalid request fields or format";
        if (error instanceof MethodArgumentNotValidException validation) {
            detail =
                    validation.getBindingResult().getFieldErrors().stream()
                            .map(field -> field.getField() + ": " + field.getDefaultMessage())
                            .distinct()
                            .sorted()
                            .reduce((a, b) -> a + "; " + b)
                            .orElse(detail);
        }
        return problem(HttpStatus.BAD_REQUEST, detail, request);
    }

    @ExceptionHandler({
        DataIntegrityViolationException.class,
        ConcurrencyFailureException.class,
        ObjectOptimisticLockingFailureException.class
    })
    ProblemDetail conflict(HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "Duplicate, referenced resource, or concurrent change conflicts with this"
                        + " operation",
                request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception error, HttpServletRequest request) {
        if (error instanceof org.springframework.web.ErrorResponse framework) {
            return problem(
                    HttpStatus.valueOf(framework.getStatusCode().value()),
                    framework.getBody().getDetail(),
                    request);
        }
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class)
                .error("Unexpected request failure: {}", error.getClass().getSimpleName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected internal failure", request);
    }

    public static ProblemDetail problem(
            HttpStatus status, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("correlationId", MDC.get("correlationId"));
        return problem;
    }
}
