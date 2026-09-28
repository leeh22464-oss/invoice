package com.acme.invoice.identity;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Profile("!smoke")
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUser currentUser;

    public AuthService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            CurrentUser currentUser) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
    }

    @Transactional
    public User register(RegisterRequest request) {
        if (users.findByEmailIgnoreCase(request.email()).isPresent()) {
            throw emailExists();
        }

        User user = new User(
                UUID.randomUUID(),
                request.email(),
                passwordEncoder.encode(request.password()),
                UserStatus.ACTIVE,
                Instant.now());
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw emailExists();
        }
    }

    @Transactional(readOnly = true)
    public User login(LoginRequest request) {
        User user = users.findByEmailIgnoreCase(request.email())
                .orElseThrow(AuthService::invalidCredentials);
        if (user.getStatus() != UserStatus.ACTIVE
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return user;
    }

    @Transactional(readOnly = true)
    public User current() {
        UUID userId = currentUser.requireId();
        return users.findByIdAndId(userId, userId)
                .orElseThrow(() -> new AuthException(
                        HttpStatus.NOT_FOUND,
                        "USER_NOT_FOUND",
                        "User not found"));
    }

    private static AuthException emailExists() {
        return new AuthException(
                HttpStatus.CONFLICT,
                "EMAIL_ALREADY_EXISTS",
                "An account with this email already exists");
    }

    private static AuthException invalidCredentials() {
        return new AuthException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Invalid email or password");
    }
}
