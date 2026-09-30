package com.acme.invoice.credential;

import com.acme.invoice.ocr.PaddleTokenClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OcrCredentialControllerTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "0123456789abcdef0123456789abcdef");
        registry.add("app.jwt.secure-cookie", () -> "false");
        registry.add("app.credential.master-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OcrCredentialService credentialService;
    @MockitoBean PaddleTokenClient tokenClient;

    @BeforeEach
    void clean() {
        jdbc.update("delete from users");
        when(tokenClient.validate(anyString(), anyString()))
                .thenReturn(new CredentialValidation(true, "Credentials are valid"));
    }

    @Test
    void lifecycleNeverReturnsSecretOrCipherFields() throws Exception {
        Cookie token = register("owner@example.com");

        mvc.perform(get("/api/settings/ocr-credentials").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        mvc.perform(post("/api/settings/ocr-credentials/test").with(csrf()).cookie(token))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/settings/ocr-credentials")
                        .with(csrf()).cookie(token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"abcdefgh1234\",\"secretKey\":\"super-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.apiKeyMask").value("abcd****1234"))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.secretKey").doesNotExist())
                .andExpect(jsonPath("$.ciphertext").doesNotExist())
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("super-secret"))));

        Integer securelyStored = jdbc.queryForObject("""
                select count(*) from ocr_credentials
                where api_key_iv <> secret_key_iv
                  and api_key_ciphertext <> convert_to('abcdefgh1234', 'UTF8')
                  and secret_key_ciphertext <> convert_to('super-secret', 'UTF8')
                """, Integer.class);
        assertThat(securelyStored).isEqualTo(1);

        mvc.perform(post("/api/settings/ocr-credentials/test").with(csrf()).cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validationStatus").value("VALID"));

        mvc.perform(delete("/api/settings/ocr-credentials").with(csrf()).cookie(token))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/settings/ocr-credentials").with(csrf()).cookie(token))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsInvalidInputAndPreservesExistingCredentialOnRemoteInvalid() throws Exception {
        Cookie token = register("preserve@example.com");
        save(token, "abcdefgh1234", "old-secret");
        when(tokenClient.validate("replacement", "bad-secret"))
                .thenReturn(new CredentialValidation(false, "Credentials are invalid"));

        mvc.perform(put("/api/settings/ocr-credentials")
                        .with(csrf()).cookie(token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"replacement\",\"secretKey\":\"bad-secret\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("bad-secret"))));

        mvc.perform(get("/api/settings/ocr-credentials").cookie(token))
                .andExpect(jsonPath("$.apiKeyMask").value("abcd****1234"));

        mvc.perform(put("/api/settings/ocr-credentials")
                        .with(csrf()).cookie(token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\" \",\"secretKey\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/ocr-credentials")
                        .with(csrf()).cookie(token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + "a".repeat(513)
                                + "\",\"secretKey\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isolatesUsersAndEnforcesAuthenticationAndCsrf() throws Exception {
        Cookie first = register("first@example.com");
        Cookie second = register("second@example.com");
        save(first, "firstkey1234", "first-secret");

        mvc.perform(get("/api/settings/ocr-credentials").cookie(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        mvc.perform(get("/api/settings/ocr-credentials"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/settings/ocr-credentials").cookie(first)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"abcdefgh\",\"secretKey\":\"secret\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/ocr-credentials/test").cookie(first))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/settings/ocr-credentials").cookie(first))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentFirstSaveForSameUserAtomicallyUpsertsOneRow() throws Exception {
        register("concurrent@example.com");
        UUID userId = userId("concurrent@example.com");
        var barrier = new CyclicBarrier(2);
        when(tokenClient.validate(anyString(), anyString())).thenAnswer(invocation -> {
            barrier.await(5, TimeUnit.SECONDS);
            return new CredentialValidation(true, "Credentials are valid");
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> credentialService.save(
                    userId, new CredentialRequest("first-key", "first-secret")));
            var second = executor.submit(() -> credentialService.save(
                    userId, new CredentialRequest("second-key", "second-secret")));

            assertThat(first.get(10, TimeUnit.SECONDS).configured()).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS).configured()).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from ocr_credentials where user_id = ?",
                Integer.class,
                userId)).isEqualTo(1);
    }

    @Test
    void remoteValidationRunsOutsideTransactionsForSaveAndRetest() throws Exception {
        register("transaction@example.com");
        UUID userId = userId("transaction@example.com");
        List<Boolean> transactionStates = new CopyOnWriteArrayList<>();
        when(tokenClient.validate(anyString(), anyString())).thenAnswer(invocation -> {
            transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
            return new CredentialValidation(true, "Credentials are valid");
        });

        credentialService.save(userId, new CredentialRequest("outside-key", "outside-secret"));
        credentialService.test(userId);

        assertThat(transactionStates).containsExactly(false, false);
    }

    private Cookie register(String email) throws Exception {
        return mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"StrongPass1!\"}"))
                .andReturn().getResponse().getCookie("access_token");
    }

    private UUID userId(String email) {
        return jdbc.queryForObject(
                "select id from users where email = ?",
                UUID.class,
                email);
    }

    private void save(Cookie token, String apiKey, String secretKey) throws Exception {
        mvc.perform(put("/api/settings/ocr-credentials").with(csrf()).cookie(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + apiKey + "\",\"secretKey\":\"" + secretKey + "\"}"))
                .andExpect(status().isOk());
    }
}
