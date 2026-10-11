package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.RegisterRequest;
import com.titan.titancorebanking.enums.UserTier;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + id));
    }

    public User createUser(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new RuntimeException("Username already exists");
        }
        User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .pin(passwordEncoder.encode(request.pin()))
                .build();
        return userRepository.save(user);
    }

    @Transactional
    public User updateUserTier(Long userId, UserTier newTier) {
        User user = getUserById(userId);
        user.setTier(newTier);
        return userRepository.save(user);
    }
}