package com.titan.titancorebanking.dto.response;

public record AuthResponse(
        String token,
        Long id
) {}