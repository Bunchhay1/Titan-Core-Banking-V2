package com.titan.titancorebanking.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

// ✅ ត្រូវប្រាកដថាប្រើ "record" (មិនមែន class)
// ✅ ត្រូវប្រាកដថាឈ្មោះ variable គឺ "riskLevel" (camelCase)
public record RiskCheckResponse(
        @JsonProperty("risk_level") String riskLevel,
        @JsonProperty("action") String action
) {}