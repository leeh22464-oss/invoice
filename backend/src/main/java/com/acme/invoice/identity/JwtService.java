package com.acme.invoice.identity;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("!smoke")
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(
            @Value("${app.jwt.secret:}") String configuredSecret,
            @Value("${app.jwt.ttl:PT1H}") Duration ttl) {
        byte[] secret = secretBytes(configuredSecret);
        if (secret.length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET must contain at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttl = ttl;
    }

    public String issue(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public Optional<UUID> verify(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Optional.of(UUID.fromString(subject));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static byte[] secretBytes(String configuredSecret) {
        if (configuredSecret == null) {
            return new byte[0];
        }
        if (configuredSecret.startsWith("base64:")) {
            try {
                return Base64.getDecoder().decode(configuredSecret.substring("base64:".length()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("APP_JWT_SECRET is not valid Base64", exception);
            }
        }
        return configuredSecret.getBytes(StandardCharsets.UTF_8);
    }
}
