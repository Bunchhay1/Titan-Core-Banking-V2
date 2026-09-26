package com.titan.titancorebanking.service.imple;

import com.titan.titancorebanking.dto.request.LoginRequest;
import com.titan.titancorebanking.dto.request.RegisterRequest;
import com.titan.titancorebanking.dto.response.AuthResponse;
import com.titan.titancorebanking.dto.response.AuthenticationResponse;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.UserRepository;
import com.titan.titancorebanking.mapper.UserMapper; // Ensure this is imported if used
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

// ❌ លុបបន្ទាត់នេះចោល: import com.titan.titancorebanking.config.JwtService;
// (មិនត្រូវការ Import ទេ ព្រោះវានៅក្នុង Package ជាមួយគ្នា)

@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService; // ✅ វានឹងស្គាល់ Class នេះដោយស្វ័យប្រវត្តិ
    private final AuthenticationManager authenticationManager;
    private final UserMapper userMapper;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exists");
        }

        var user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .pin(passwordEncoder.encode(request.getPin()))
                .role("ROLE_USER") // ✅ Set default role
                .build();

        userRepository.save(user);

        var jwtToken = jwtService.generateToken(user);
        return AuthResponse.builder()
                .token(jwtToken)
                .id(user.getId())
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );
        var user = userRepository.findByUsername(request.getUsername())
                .orElseThrow();
        var jwtToken = jwtService.generateToken(user);
        return AuthResponse.builder()
                .token(jwtToken)
                .id(user.getId())
                .build();
    }
}
