package com.titan.titancorebanking.service.imple;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
@Slf4j
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secretKey;

    @Value("${app.jwt.expiration-ms:86400000}")
    private long jwtExpirationMs;

    @Value("${app.jwt.issuer:titan-banking}")
    private String jwtIssuer;

    @Value("${app.jwt.audience:titan-api}")
    private String jwtAudience;

    private Key cachedSigningKey;

    // [MODIFIED] Cached the signing key at startup to eliminate heavy CPU overhead on every request.
    // បង្កើត Key សម្រាប់ Sign តែម្តងនៅពេល Server ដើរ (Startup) ដើម្បីកុំឲ្យ CPU ធ្វើការបម្លែងកូដដដែលៗរាល់ពេលមាន Request ចូល។ វាជួយសន្សំសំចៃ CPU និងធ្វើឱ្យប្រព័ន្ធដើរលឿនជាងមុនឆ្ងាយ។
    @PostConstruct
    protected void init() {
        Assert.hasText(secretKey, "JWT Secret Key must be configured in application properties");

        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);

        // [MODIFIED] Added startup security guardrail to verify minimum cryptographic strength for HS256.
        // ត្រួតពិនិត្យប្រវែងកូដសម្ងាត់ (Secret Key)។ បើវាមិនដល់ 32 bytes (256 bits) ទេ វាមានហានិភ័យខ្ពស់ក្នុងការត្រូវ Hacker បំបែកបាន។ ប្រព័ន្ធនឹងលោតសារព្រមានតាំងពីពេលបើក Server។
        if (keyBytes.length < 32) {
            log.warn("SECURITY ALERT: JWT Secret Key is less than 256 bits (32 bytes). This is cryptographically weak for HS256.");
        }

        this.cachedSigningKey = Keys.hmacShaKeyFor(keyBytes);
    }

    // [MODIFIED] Enforced 'final' parameters to guarantee immutability during security evaluation.
    public String extractUsername(final String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(final String token, final Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String generateToken(final UserDetails userDetails) {
        return generateToken(Map.of(), userDetails); // Map.of() provides a zero-allocation immutable empty map
    }

    public String generateToken(final Map<String, Object> extraClaims, final UserDetails userDetails) {
        var claims = new HashMap<>(extraClaims);

        if (jwtIssuer != null && !jwtIssuer.isBlank()) {
            claims.putIfAbsent("iss", jwtIssuer);
        }
        if (jwtAudience != null && !jwtAudience.isBlank()) {
            claims.putIfAbsent("aud", jwtAudience);
        }

        // [MODIFIED] Replaced legacy System.currentTimeMillis() with robust java.time.Instant
        // ប្រើប្រាស់ `Instant.now()` ជំនួសអោយកូដចាស់ដើម្បីគ្រប់គ្រងពេលវេលាបានជាក់លាក់ និងត្រឹមត្រូវតាមស្តង់ដារ Java ទំនើប។
        var now = Instant.now();
        var validity = now.plusMillis(jwtExpirationMs);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userDetails.getUsername())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(validity))
                .signWith(cachedSigningKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean isTokenValid(final String token, final UserDetails userDetails) {
        final String username = extractUsername(token);
        // [MODIFIED] Null-safety guard before calling .equals() to prevent NullPointerException.
        return (username != null && username.equals(userDetails.getUsername())) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(final String token) {
        return extractExpiration(token).before(Date.from(Instant.now()));
    }

    private Date extractExpiration(final String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(final String token) {
        // [MODIFIED] Uses the pre-computed cachedSigningKey instead of regenerating it dynamically.
        return Jwts.parserBuilder()
                .setSigningKey(cachedSigningKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}