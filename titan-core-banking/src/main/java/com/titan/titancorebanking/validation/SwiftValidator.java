package com.titan.titancorebanking.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class SwiftValidator implements ConstraintValidator<ValidSwift, String> {

    // Regex: 4 Letters (Bank) + 2 Letters (Country) + 2 Alphanumeric (Location) + Optional 3 Alphanumeric (Branch)
    private static final String SWIFT_REGEX = "^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true; // Null is valid (because it's optional unless @NotNull is used)
        }
        return value.toUpperCase().matches(SWIFT_REGEX);
    }
}