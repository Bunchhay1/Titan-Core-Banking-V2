package com.titan.promotions.wasm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class WasmRule {
    private Long ruleId;
    private String ruleName;
    private byte[] wasmBinary;
    private String functionName;
    private Integer priority;
}
