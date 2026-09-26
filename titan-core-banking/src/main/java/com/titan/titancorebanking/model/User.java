package com.titan.titancorebanking.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.titan.titancorebanking.enums.UserTier;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User implements UserDetails, Serializable {

    // ... (Fields ផ្សេងទៀតនៅដដែល: id, username, password...) ...
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firstName;
    private String lastName;

    @Column(nullable = false, unique = true)
    private String username;
    private String email;
    @Column(nullable = false)
    private String password;
    private String pin;
    private String role;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private UserTier tier = UserTier.STANDARD;

    @Builder.Default
    private boolean accountNonLocked = true;

    // 🛑 STOP THE INFINITE LOOP HERE!
    @JsonIgnore // ✅ Add this annotation
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Account> accounts;

    // ... (Methods ផ្សេងទៀតនៅដដែល: getAuthorities, etc.) ...

    public String getFullName() {
        return firstName + " " + lastName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (role == null || role.isEmpty()) {
            return List.of(new SimpleGrantedAuthority("ROLE_USER"));
        }
        return List.of(new SimpleGrantedAuthority(role));
    }

    @Override
    public String getPassword() { return password; }
    @Override
    public String getUsername() { return username; }
    @Override
    public boolean isAccountNonExpired() { return true; }
    @Override
    public boolean isAccountNonLocked() { return accountNonLocked; }
    @Override
    public boolean isCredentialsNonExpired() { return true; }
    @Override
    public boolean isEnabled() { return true; }
}