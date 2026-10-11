package com.titan.titancorebanking.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Optimized IBAN Validator.
 * Prevents regex recompilation and optimizes the Modulo 97 check.
 */
public class IbanValidator implements ConstraintValidator<ValidIBAN, String> {

    private static final Pattern IBAN_FORMAT_PATTERN = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$");
    private static final BigInteger MODULUS = new BigInteger("97");

    @Override
    public boolean isValid(String iban, ConstraintValidatorContext context) {
        if (iban == null || iban.isEmpty()) {
            return true;
        }

        String normalizedIban = iban.trim().toUpperCase();

        if (!IBAN_FORMAT_PATTERN.matcher(normalizedIban).matches()) {
            return false;
        }

        return mod97Check(normalizedIban);
    }

    private boolean mod97Check(String iban) {
        try {
            String rearranged = iban.substring(4) + iban.substring(0, 4);
            StringBuilder numericIban = new StringBuilder(rearranged.length() * 2);

            for (int i = 0; i < rearranged.length(); i++) {
                char ch = rearranged.charAt(i);
                if (Character.isLetter(ch)) {
                    numericIban.append(ch - 'A' + 10);
                } else {
                    numericIban.append(ch);
                }
            }

            BigInteger bigInt = new BigInteger(numericIban.toString());
            return bigInt.mod(MODULUS).intValue() == 1;
        } catch (Exception e) {
            return false;
        }
    }
}