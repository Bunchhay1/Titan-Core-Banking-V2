package com.titan.titancorebanking.dto.response;

/**
 * Immutable User Response Record.
 * Ensures the encrypted password and PIN hashes are structurally prevented from
 * being serialized to the client.
 */
public record UserResponse(
        Long id,
        String firstName,
        String lastName,
        String username,
        String email
) {}