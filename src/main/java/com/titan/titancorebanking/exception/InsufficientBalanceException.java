package com.titan.titancorebanking.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Centralized custom exceptions.
 * Utilizing @ResponseStatus allows these exceptions to map to correct HTTP codes
 * even if they bypass the GlobalExceptionHandler under specific Filter-level conditions.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(String message) {
        super(message);
    }
}