package com.titan.titancorebanking.exception;

import com.titan.titancorebanking.dto.response.ErrorResponse;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidationExceptions(MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value",
                        (existing, replacement) -> existing
                ));

        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Validation Failed", "Input validation failed.", request, errors);
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<Object> handleInsufficientBalance(InsufficientBalanceException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Business Rule Violation", ex.getMessage(), request, null);
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<Object> handleRedisDown(RedisConnectionFailureException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Cache Unavailable", "Cache service is temporarily unavailable. Please retry.", request, null);
    }

    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    public ResponseEntity<Object> handleAccessDenied(Exception ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Access Denied", "You do not have permission to access this resource.", request, null);
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<Object> handleDatabasePoolExhaustion(Exception ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Service Temporarily Busy", "Database connection pool is saturated. Please retry.", request, null);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Object> handleRuntimeException(RuntimeException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Business Logic Error", ex.getMessage(), request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGlobalException(Exception ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred.", request, null);
    }

    private ResponseEntity<Object> buildErrorResponse(HttpStatus status, String error, String message, WebRequest request, Map<String, String> details) {
        String path = request.getDescription(false).replace("uri=", "");

        if (details != null) {
            return ResponseEntity.status(status).body(Map.of(
                    "timestamp", LocalDateTime.now(),
                    "status", status.value(),
                    "error", error,
                    "message", message,
                    "details", details,
                    "path", path
            ));
        }

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(error)
                .message(message)
                .path(path)
                .build();

        return new ResponseEntity<>(errorResponse, status);
    }
}