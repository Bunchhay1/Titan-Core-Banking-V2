package com.titan.titancorebanking.dto.request;

import java.math.BigDecimal;

// Java Record (ថ្មី និងខ្លី) - សម្រាប់វេចខ្ចប់ទិន្នន័យផ្ញើទៅ Python
public record RiskCheckRequest(String username, BigDecimal amount) {}