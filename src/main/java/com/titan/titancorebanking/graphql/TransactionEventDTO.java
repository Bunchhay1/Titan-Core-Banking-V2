package com.titan.titancorebanking.graphql;

public record TransactionEventDTO(
        Long accountId,
        String transactionId,
        String amount,
        String currency,
        String type,
        String status,
        String sourceAccountNumber,
        String targetAccountNumber,
        String timestamp
) {}