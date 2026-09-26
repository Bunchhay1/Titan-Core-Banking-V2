package com.titan.titancorebanking.mapper;

import com.titan.titancorebanking.dto.request.RegisterRequest;
import com.titan.titancorebanking.dto.response.UserResponse;
// ✅ FIX IMPORT: Use MODEL, not ENTITY
import com.titan.titancorebanking.model.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public User toEntity(RegisterRequest request) {
        if (request == null) {
            return null;
        }
        return User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .username(request.getUsername())
                .email(request.getEmail())
                .password(request.getPassword())
                .role("ROLE_USER")
                .build();
    }

    public UserResponse toUserResponse(User user) {
        if (user == null) {
            return null;
        }
        return UserResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }
}