package com.titan.titancorebanking.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = SwiftValidator.class)
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSwift {
    String message() default "❌ Invalid SWIFT/BIC code";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}