package com.titan.titancorebanking.constant;

import java.math.BigDecimal;

public final class TransactionConstants {

    private TransactionConstants() {} // Prevent instantiation

    public static final BigDecimal MAX_TRANSACTION_AMOUNT = new BigDecimal("10000000.0000");
    public static final int MAX_MONETARY_SCALE = 4;

    public static final int DEFAULT_PAGE_NUMBER = 0;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MIN_PAGE_SIZE = 1;
}
