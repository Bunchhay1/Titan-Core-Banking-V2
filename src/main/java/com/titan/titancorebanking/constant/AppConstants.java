package com.titan.titancorebanking.constant;

public final class AppConstants {
    private AppConstants() {} // Prevent instantiation

    public static final String TOPIC_TRANSACTION_COMPLETED = "banking.transactions.completed";
    public static final String URI_TRANSFER = "/api/v1/transactions/transfer";
    public static final String URI_WITHDRAW = "/api/v1/transactions/withdraw";
    public static final String URI_DEPOSIT = "/api/v1/transactions/deposit";
    public static final String LOCKDOWN_FLAG = "dms:lockdown_active";
}