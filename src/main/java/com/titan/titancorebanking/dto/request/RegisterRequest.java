package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterRequest {
    // ✅ Fix: Use CamelCase (firstName, lastName)
    private String firstName;
    private String lastName;

    private String username;
    private String email;

    @NotBlank(message = "Password cannot be null")
    private String password;

    private String pin;
}