package com.titan.titancorebanking.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder // ✅ THIS FIXES the "builder()" error
@AllArgsConstructor
@NoArgsConstructor
public class UserResponse implements Serializable {
    private Long id;
    private String username;
    private String email;

    // ✅ Updated to match UserMapper (firstName/lastName instead of fullName)
    private String firstName;
    private String lastName;
}