package com.acme.invoice.identity;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@Profile("!smoke")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final boolean secureCookie;

    public AuthController(
            AuthService authService,
            JwtService jwtService,
            @Value("${app.jwt.secure-cookie:true}") boolean secureCookie) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/register")
    ResponseEntity<UserResponse> register(
            @Valid @RequestBody RegisterRequest request,
            CsrfToken csrfToken) {
        User user = authService.register(request);
        return authenticatedResponse(user, csrfToken, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    ResponseEntity<UserResponse> login(
            @Valid @RequestBody LoginRequest request,
            CsrfToken csrfToken) {
        User user = authService.login(request);
        return authenticatedResponse(user, csrfToken, HttpStatus.OK);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        ResponseCookie expired = ResponseCookie.from("access_token", "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .build();
    }

    @GetMapping("/me")
    UserResponse me() {
        return UserResponse.from(authService.current());
    }

    private ResponseEntity<UserResponse> authenticatedResponse(
            User user,
            CsrfToken csrfToken,
            HttpStatus status) {
        ResponseCookie accessToken = ResponseCookie
                .from("access_token", jwtService.issue(user.getId()))
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .build();
        ResponseCookie xsrfToken = ResponseCookie
                .from("XSRF-TOKEN", csrfToken.getToken())
                .httpOnly(false)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, accessToken.toString());
        headers.add(HttpHeaders.SET_COOKIE, xsrfToken.toString());
        return new ResponseEntity<>(UserResponse.from(user), headers, status);
    }
}
