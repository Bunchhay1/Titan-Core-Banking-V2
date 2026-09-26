package com.titan.titancorebanking.service.imple; // ⚠️ Check Package

import com.titan.titancorebanking.enums.Currency;
import org.springframework.stereotype.Service; // ⚠️ Check Import
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Service // ✅ ត្រូវតែមានពាក្យនេះដាច់ខាត!
public class ExchangeRateService {

    // Base Currency: USD
    // Rates: 1 USD = X Currency
    private final Map<Currency, BigDecimal> rates = Map.of(
            Currency.USD, BigDecimal.ONE,
            Currency.KHR, new BigDecimal("4100"),
            Currency.EUR, new BigDecimal("0.92")
    );

    public BigDecimal getRate(Currency from, Currency to) {
        if (from == to) return BigDecimal.ONE;

        // Logic: Convert FROM -> USD -> TO
        BigDecimal fromRate = rates.get(from);
        BigDecimal toRate = rates.get(to);

        // Formula: (Amount / fromRate) * toRate
        // Example: KHR -> EUR = (Amount / 4100) * 0.92

        return toRate.divide(fromRate, 6, RoundingMode.HALF_UP);
    }

    public BigDecimal convert(BigDecimal amount, Currency from, Currency to) {
        BigDecimal rate = getRate(from, to);
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}