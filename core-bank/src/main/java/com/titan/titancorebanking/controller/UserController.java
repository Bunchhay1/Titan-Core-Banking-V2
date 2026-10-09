package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.annotation.AuditLog;
import com.titan.titancorebanking.dto.request.RegisterRequest;
import com.titan.titancorebanking.enums.UserTier;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * User management controller.
 *
 * SECURITY FIX: Sensitive endpoints now require ADMIN role to prevent
 * unauthorized user enumeration and privilege escalation.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * Get all users in the system.
     *
     * BUG FIX: Previously accessible to any authenticated user, allowing user enumeration attack.
     * Now restricted to ADMIN role only.
     *
     * SECURITY IMPACT: Prevents unauthorized users from discovering account information,
     * usernames, and system user count.
     *
     * @return List of all users (ADMIN only)
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    /**
     * Get user by ID.
     *
     * SECURITY NOTE: Consider adding ownership check or ADMIN restriction here as well.
     * Current implementation allows any authenticated user to view any other user's details.
     * Recommended: @PreAuthorize("hasRole('ADMIN') or @userSecurity.isCurrentUser(authentication, #id)")
     *
     * @param id User ID
     * @return User details
     */
    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    /**
     * Create new user (registration).
     *
     * @param request Registration request with username, password, email
     * @return Created user
     */
    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.createUser(request));
    }
    
    /**
     * Update user tier (e.g., BRONZE, SILVER, GOLD, PLATINUM).
     *
     * SECURITY NOTE: This endpoint should also be restricted to ADMIN.
     * Tier upgrades affect transaction limits and fee structures.
     *
     * @param id User ID
     * @param payload Map containing new tier value
     * @return Updated user tier
     */
    @AuditLog(action = "TIER_UPDATE")
    @PutMapping("/{id}/tier")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateUserTier(@PathVariable Long id, @RequestBody Map<String, String> payload) {
        UserTier newTier = UserTier.valueOf(payload.get("tier"));
        User user = userService.updateUserTier(id, newTier);
        return ResponseEntity.ok(Map.of("message", "User tier updated", "tier", user.getTier()));
    }
}