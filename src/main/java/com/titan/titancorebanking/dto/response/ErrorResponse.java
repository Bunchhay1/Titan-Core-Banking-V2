package com.titan.titancorebanking.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int status;       // ឧ. 400, 403, 404
    private String error;     // ឧ. "Bad Request"
    private String message;   // ឧ. "Insufficient balance"
    private String path;      // ឧ. "/api/transactions/transfer"

}