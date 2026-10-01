package com.acme.invoice.identity;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "0123456789abcdef0123456789abcdef");
        registry.add("app.jwt.secure-cookie", () -> "false");
        registry.add("app.credential.master-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FilterChainProxy filterChainProxy;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from users");
        restoreCookieCsrfRepository();
    }

    @Test
    void registrationNormalizesEmailHashesPasswordAndSetsSafeCookies() throws Exception {
        var result = mvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"  Alice@Example.COM ","password":"StrongPass1!"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(cookie().httpOnly("access_token", true))
                .andExpect(cookie().secure("access_token", false))
                .andExpect(cookie().path("access_token", "/"))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andReturn();

        Cookie accessToken = result.getResponse().getCookie("access_token");
        assertThat(accessToken).isNotNull();
        assertThat(accessToken.getValue()).isNotBlank();

        String passwordHash = jdbc.queryForObject(
                "select password_hash from users where email = ?",
                String.class,
                "alice@example.com");
        assertThat(passwordHash)
                .startsWith("$2")
                .isNotEqualTo("StrongPass1!");
    }

    @Test
    void duplicateEmailReturnsConflictWithStableCode() throws Exception {
        register("duplicate@example.com", "StrongPass1!");

        mvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"DUPLICATE@example.com","password":"AnotherPass2@"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void loginReturnsSafeUserAndFreshCookies() throws Exception {
        register("login@example.com", "StrongPass1!");

        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"LOGIN@example.com","password":"StrongPass1!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("login@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(cookie().httpOnly("access_token", true))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    void invalidCredentialsUseGenericUnauthorizedResponse() throws Exception {
        register("login@example.com", "StrongPass1!");

        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"login@example.com","password":"WrongPass1!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"missing@example.com","password":"WrongPass1!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void disabledUserCannotLogin() throws Exception {
        register("disabled@example.com", "StrongPass1!");
        jdbc.update("update users set status = 'DISABLED' where email = 'disabled@example.com'");

        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"disabled@example.com","password":"StrongPass1!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void logoutExpiresAccessToken() throws Exception {
        Cookie token = register("logout@example.com", "StrongPass1!");

        mvc.perform(post("/api/auth/logout")
                        .with(csrf())
                        .cookie(token))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("access_token", 0))
                .andExpect(cookie().path("access_token", "/"));
    }

    @Test
    void unauthenticatedLogoutWithValidCsrfExpiresAccessToken() throws Exception {
        mvc.perform(post("/api/auth/logout")
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value("access_token", ""))
                .andExpect(cookie().maxAge("access_token", 0))
                .andExpect(cookie().httpOnly("access_token", true))
                .andExpect(cookie().secure("access_token", false))
                .andExpect(cookie().path("access_token", "/"))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));
    }

    @Test
    void logoutWithMalformedAccessTokenAndValidCsrfExpiresAccessToken() throws Exception {
        mvc.perform(post("/api/auth/logout")
                        .with(csrf())
                        .cookie(new Cookie("access_token", "not-a-jwt")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value("access_token", ""))
                .andExpect(cookie().maxAge("access_token", 0))
                .andExpect(cookie().httpOnly("access_token", true))
                .andExpect(cookie().secure("access_token", false))
                .andExpect(cookie().path("access_token", "/"))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));
    }

    @Test
    void unauthenticatedLogoutWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isForbidden())
                .andExpect(cookie().doesNotExist("access_token"));
    }

    @Test
    void meReturnsAuthenticatedNormalizedUser() throws Exception {
        Cookie token = register("me@example.com", "StrongPass1!");

        mvc.perform(get("/api/auth/me").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void stateChangingRequestWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"csrf@example.com","password":"StrongPass1!"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void meRejectsUnauthenticatedRequest() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousMeReturnsUnauthorizedJsonAndReadableCsrfCookie() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {"code":"UNAUTHORIZED","message":"Authentication required"}
                        """))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andExpect(cookie().value("XSRF-TOKEN", not(emptyOrNullString())));
    }

    @Test
    void registerAcceptsRawCsrfTokenIssuedByAnonymousMe() throws Exception {
        var me = mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        Cookie csrfCookie = me.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.isHttpOnly()).isFalse();
        assertThat(csrfCookie.getValue()).isNotBlank();

        mvc.perform(post("/api/auth/register")
                        .cookie(new Cookie("XSRF-TOKEN", csrfCookie.getValue()))
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"fresh@example.com","password":"StrongPass1!"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("fresh@example.com"));

        Integer created = jdbc.queryForObject(
                "select count(*) from users where email = ?",
                Integer.class,
                "fresh@example.com");
        assertThat(created).isEqualTo(1);
    }

    @Test
    void csrfRejectionReturnsJsonWithCodeAndMessage() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nocsrf@example.com","password":"StrongPass1!"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.code").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.message").value(not(emptyOrNullString())));
    }

    @Test
    void rejectsInvalidRegistrationInput() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"weak"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    // csrf() swaps the singleton CsrfFilter repository for an HttpSession one and leaves it there.
    private void restoreCookieCsrfRepository() {
        for (Filter filter : filterChainProxy.getFilters("/api/auth/me")) {
            if (filter instanceof CsrfFilter csrfFilter
                    && !(ReflectionTestUtils.getField(csrfFilter, "tokenRepository")
                    instanceof CookieCsrfTokenRepository)) {
                CookieCsrfTokenRepository cookies = CookieCsrfTokenRepository.withHttpOnlyFalse();
                cookies.setCookieCustomizer(cookie -> cookie
                        .path("/")
                        .sameSite("Lax")
                        .secure(false));
                ReflectionTestUtils.setField(csrfFilter, "tokenRepository", cookies);
            }
        }
    }

    private Cookie register(String email, String password) throws Exception {
        var response = mvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated())
                .andExpect(cookie().value("access_token", not("")))
                .andReturn()
                .getResponse();
        return response.getCookie("access_token");
    }
}
