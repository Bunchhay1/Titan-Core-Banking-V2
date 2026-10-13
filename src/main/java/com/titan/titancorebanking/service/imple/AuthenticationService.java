package com.titan.titancorebanking.service.imple;

import com.titan.titancorebanking.dto.request.LoginRequest;
import com.titan.titancorebanking.dto.request.RegisterRequest;
import com.titan.titancorebanking.dto.response.AuthResponse;
import com.titan.titancorebanking.enums.UserTier;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Prepare user entity
        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .pin(passwordEncoder.encode(request.getPin()))
                .role("ROLE_USER")
                .tier(UserTier.STANDARD)
                .accountNonLocked(true)
                .build();

        try {
            // Rely on the database unique constraint to prevent TOCTOU race conditions
            userRepository.saveAndFlush(user);
            log.info("New user registered successfully: {}", user.getUsername());
        } catch (DataIntegrityViolationException e) {
            log.warn("Registration failed - Unique constraint violation for username/email: {}", request.getUsername());
            throw new IllegalArgumentException("Username or Email already exists in the system.");
        }

        String jwtToken = jwtService.generateToken(user);

        return AuthResponse.builder()
                .token(jwtToken)
                .id(user.getId())
                .build();
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // Authenticate credentials against SecurityContext
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found after authentication"));

        if (!user.isAccountNonLocked()) {
            throw new SecurityException("Your account has been temporarily locked due to suspicious activity.");
        }

        String jwtToken = jwtService.generateToken(user);

        log.debug("User logged in successfully: {}", user.getUsername());

        return AuthResponse.builder()
                .token(jwtToken)
                .id(user.getId())
                .build();
    }
}