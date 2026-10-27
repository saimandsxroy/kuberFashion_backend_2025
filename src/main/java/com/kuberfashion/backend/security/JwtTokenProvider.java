package com.kuberfashion.backend.security;

import com.kuberfashion.backend.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.util.Date;

/**
 * Generates, validates, and parses HMAC-SHA256 JWT tokens.
 *
 * <h3>Signing key</h3>
 * The key is read from the {@code jwt.secret} property which must be supplied via the
 * {@code JWT_SECRET} environment variable. There is intentionally no hard-coded default key in
 * this class. If the property is missing or blank, the application will fail at startup with a
 * clear error message rather than silently using an insecure default.
 *
 * <h3>Secret rotation</h3>
 * <strong>Deployment action required:</strong> If this application was previously run with the
 * hard-coded default key
 * ({@code u5PS40PD1gBpFFAgD7ugXd6k9klL+h9YZMv3gIjKH3Nof/qaJf2rPbr0wrwwe6WaDfq7q3JOjwdBF/6AaKz7sQ==}),
 * all tokens signed with that key are now invalid and all active users will be logged out.
 * Generate a new random 256-bit (32-byte) base64-encoded secret and set it as
 * {@code JWT_SECRET} in your production environment before deploying. Use:
 * <pre>
 *   openssl rand -base64 32
 * </pre>
 * Store the output as the value of the {@code JWT_SECRET} environment variable.
 * Never commit this value to version control.
 */
@Component
public class JwtTokenProvider {

    /**
     * The HMAC-SHA256 signing secret, read from {@code JWT_SECRET} environment variable
     * via Spring's property binding.
     *
     * <p>No default is provided. If this property is blank at startup, {@link #validateKey()}
     * will throw an exception and abort the application context, preventing it from running
     * with an insecure configuration.
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpirationInMs;

    /**
     * Fail fast on startup if the signing key is absent or too short.
     *
     * <p>HMAC-SHA256 requires a minimum key length of 32 bytes (256 bits).
     * A key shorter than this is rejected by JJWT at runtime; catching it here
     * gives a clearer diagnostic message.
     */
    @PostConstruct
    public void validateKey() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT signing key (JWT_SECRET) is not configured. " +
                    "Generate a key with: openssl rand -base64 32");
        }
        byte[] keyBytes = jwtSecret.getBytes();
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT signing key is too short (" + keyBytes.length + " bytes). " +
                    "Minimum required for HMAC-SHA256 is 32 bytes. " +
                    "Generate a longer key with: openssl rand -base64 32");
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    public String generateToken(Authentication authentication) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();
        String role = null;
        if (userPrincipal instanceof User u) {
            role = u.getRole() != null ? u.getRole().name() : null;
        } else if (!userPrincipal.getAuthorities().isEmpty()) {
            role = userPrincipal.getAuthorities().iterator().next().getAuthority();
        }
        Date expiryDate = new Date(System.currentTimeMillis() + jwtExpirationInMs);

        return Jwts.builder()
                .setSubject(userPrincipal.getUsername())
                .setIssuedAt(new Date())
                .setExpiration(expiryDate)
                .claim("role", role)
                .signWith(getSigningKey())
                .compact();
    }

    public String generateTokenFromUsername(String username) {
        Date expiryDate = new Date(System.currentTimeMillis() + jwtExpirationInMs);

        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();

        return claims.getSubject();
    }

    public boolean validateToken(String authToken) {
        try {
            Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(authToken);
            return true;
        } catch (MalformedJwtException ex) {
            System.err.println("Invalid JWT token");
        } catch (ExpiredJwtException ex) {
            System.err.println("Expired JWT token");
        } catch (UnsupportedJwtException ex) {
            System.err.println("Unsupported JWT token");
        } catch (IllegalArgumentException ex) {
            System.err.println("JWT claims string is empty");
        }
        return false;
    }
}
