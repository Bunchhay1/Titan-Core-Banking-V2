package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.annotation.AuditLog;
import com.titan.titancorebanking.dto.request.RegisterRequest;
import com.titan.titancorebanking.enums.UserTier;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.createUser(request));
    }
    
    @AuditLog(action = "TIER_UPDATE")
    @PutMapping("/{id}/tier")
    public ResponseEntity<?> updateUserTier(@PathVariable Long id, @RequestBody Map<String, String> payload) {
        UserTier newTier = UserTier.valueOf(payload.get("tier"));
        User user = userService.updateUserTier(id, newTier);
        return ResponseEntity.ok(Map.of("message", "User tier updated", "tier", user.getTier()));
    }
}