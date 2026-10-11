package com.titan.titancorebanking.service.imple;

import com.titan.titancorebanking.dto.request.LoginRequest;
import com.titan.titancorebanking.dto.request.RegisterRequest;
import com.titan.titancorebanking.dto.response.AuthResponse;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Username already exists");
        }

        var user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .pin(passwordEncoder.encode(request.pin()))
                .role("ROLE_USER")
                .build();

        userRepository.save(user);
        var jwtToken = jwtService.generateToken(user);

        return new AuthResponse(jwtToken, user.getId());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.username(),
                        request.password()
                )
        );

        var user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        var jwtToken = jwtService.generateToken(user);

        return new AuthResponse(jwtToken, user.getId());
    }
}