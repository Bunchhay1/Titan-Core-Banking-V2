package com.titan.titancorebanking.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * Optimized SWIFT/BIC Validator.
 * Compiles the regex pattern statically to prevent reallocation and GC pressure
 * on every validation check.
 */
public class SwiftValidator implements ConstraintValidator<ValidSwift, String> {

    // Regex: 4 Letters (Bank) + 2 Letters (Country) + 2 Alphanumeric (Location) + Optional 3 Alphanumeric (Branch)
    private static final Pattern SWIFT_PATTERN = Pattern.compile("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true; // Optional field behavior
        }
        return SWIFT_PATTERN.matcher(value.toUpperCase()).matches();
    }
}