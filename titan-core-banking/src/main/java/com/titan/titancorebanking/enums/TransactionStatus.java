package com.titan.titancorebanking.enums;

public enum TransactionStatus {
    PENDING,
    SUCCESS,
    FAILED,
    PROCESSING,
    BLOCKED // For risk engine rejection
}