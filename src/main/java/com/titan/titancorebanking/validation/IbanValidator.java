package com.titan.titancorebanking.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigInteger;

public class IbanValidator implements ConstraintValidator<ValidIBAN, String> {

    @Override
    public boolean isValid(String iban, ConstraintValidatorContext context) {
        if (iban == null || iban.isEmpty()) {
            return true; // Optional field
        }

        // 1. Basic Format Check (Country Code + Check Digits + BBAN)
        // Min 15, Max 34 chars
        if (!iban.matches("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")) {
            return false;
        }

        // 2. Modulo 97 Check (The "Real" Validation)
        return mod97Check(iban);
    }

    private boolean mod97Check(String iban) {
        try {
            // A. Move first 4 chars (Country+Check) to the end
            String rearranged = iban.substring(4) + iban.substring(0, 4);

            // B. Convert Letters to Numbers (A=10, B=11 ... Z=35)
            StringBuilder numericIban = new StringBuilder();
            for (char ch : rearranged.toCharArray()) {
                if (Character.isLetter(ch)) {
                    numericIban.append(ch - 'A' + 10);
                } else {
                    numericIban.append(ch);
                }
            }

            // C. Calculate Modulo 97
            BigInteger bigInt = new BigInteger(numericIban.toString());
            return bigInt.mod(new BigInteger("97")).intValue() == 1;

        } catch (Exception e) {
            return false;
        }
    }
}