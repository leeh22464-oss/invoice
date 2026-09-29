package com.acme.invoice.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UserIsolationTest {

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
        registry.add("app.credential.master-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void scopedIdentityLookupReturnsOnlyTheRequestedOwner() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        insertUser(ownerId, "owner@example.com");
        insertUser(otherUserId, "other@example.com");

        var ownerResult = users.findByIdAndId(ownerId, ownerId);
        var crossUserResult = users.findByIdAndId(ownerId, otherUserId);

        assertThat(ownerResult).isPresent();
        assertThat(ownerResult.orElseThrow().getId()).isEqualTo(ownerId);
        assertThat(crossUserResult).isEmpty();
    }

    private void insertUser(UUID id, String email) {
        jdbc.update("""
                        insert into users(id, email, password_hash, status, created_at)
                        values (?, ?, ?, 'ACTIVE', ?)
                        """,
                id,
                email,
                "$2a$10$01234567890123456789012345678901234567890123456789012",
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
