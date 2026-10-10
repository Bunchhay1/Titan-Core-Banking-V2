package com.titan.titancorebanking.service.imple;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secretKey;

    @Value("${app.jwt.expiration-ms:86400000}")
    private long jwtExpiration;

    @Value("${app.jwt.issuer:titan-banking}")
    private String jwtIssuer;

    @Value("${app.jwt.audience:titan-api}")
    private String jwtAudience;

    private SecretKey signingKey;

    @PostConstruct
    protected void init() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("CRITICAL: JWT Secret must be at least 256 bits (32 bytes) to meet enterprise security standards.");
        }
        // MODIFICATION: Explicitly cast to SecretKey for modern JJWT compatibility
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claims != null ? claimsResolver.apply(claims) : null;
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>(extraClaims);

        if (jwtIssuer != null && !jwtIssuer.isBlank()) {
            claims.putIfAbsent("iss", jwtIssuer);
        }
        if (jwtAudience != null && !jwtAudience.isBlank()) {
            claims.putIfAbsent("aud", jwtAudience);
        }

        Instant now = Instant.now();

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userDetails.getUsername())
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusMillis(jwtExpiration)))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            // MODIFICATION: Parse token exactly ONCE to save CPU cycles
            final Claims claims = extractAllClaims(token);
            if (claims == null) return false;

            final String username = claims.getSubject();
            final Date expiration = claims.getExpiration();

            boolean isExpired = expiration != null && expiration.before(Date.from(Instant.now()));

            return (username != null && username.equals(userDetails.getUsername())) && !isExpired;

        } catch (Exception e) {
            log.warn("JWT Validation failed for user {}: {}", userDetails.getUsername(), e.getMessage());
            return false;
        }
    }

    private Claims extractAllClaims(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .requireIssuer(jwtIssuer)
                    .requireAudience(jwtAudience)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException | IllegalArgumentException e) {
            // MODIFICATION: Fail gracefully on malformed, expired, or tampered tokens
            log.error("Invalid JWT Token structure or signature: {}", e.getMessage());
            return null;
        }
    }
}