package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.annotation.AuditLog;
import com.titan.titancorebanking.dto.request.RegisterRequest;
import com.titan.titancorebanking.dto.response.UserResponse;
import com.titan.titancorebanking.enums.UserTier;
import com.titan.titancorebanking.mapper.UserMapper;
import com.titan.titancorebanking.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Hardened User Controller.
 * Implements strict RBAC to ensure standard users cannot query the global user list
 * or elevate their own tier status. Uses UserResponse DTOs to prevent leaking
 * password hashes or PINs, even though they are marked WRITE_ONLY in the entity.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "User Management", description = "User profile and tier management")
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get all users (Admin only)")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        List<UserResponse> users = userService.getAllUsers().stream()
                .map(userMapper::toUserResponse)
                .toList();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
    @Operation(summary = "Get user by ID")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userMapper.toUserResponse(userService.getUserById(id)));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get current authenticated user")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(userMapper.toUserResponse(
                (com.titan.titancorebanking.model.User) userDetails));
    }

    @AuditLog(action = "TIER_UPDATE")
    @PutMapping("/{id}/tier")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update user tier (Admin only)")
    public ResponseEntity<Map<String, Object>> updateUserTier(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTierRequest payload) {

        UserTier newTier = UserTier.valueOf(payload.tier().toUpperCase());
        com.titan.titancorebanking.model.User user = userService.updateUserTier(id, newTier);

        return ResponseEntity.ok(Map.of(
                "message", "User tier updated successfully",
                "userId", user.getId(),
                "newTier", user.getTier().name()
        ));
    }

    public record UpdateTierRequest(String tier) {}
}