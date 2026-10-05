package com.acme.invoice.erp;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ErpControllerTest {

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
    @Autowired ErpService erpService;

    @MockitoSpyBean
    ErpPushClient pushClient;

    @BeforeEach
    void clean() {
        jdbc.update("delete from erp_push_receipts");
        jdbc.update("delete from audit_events");
        jdbc.update("delete from duplicate_matches");
        jdbc.update("delete from verification_issues");
        jdbc.update("delete from invoices");
        jdbc.update("delete from normalized_invoices");
        jdbc.update("delete from ocr_results");
        jdbc.update("delete from invoice_files");
        jdbc.update("delete from import_batches");
        jdbc.update("delete from users");
    }

    @Test
    void erpQueryExposesOnlyPassedInvoices() throws Exception {
        Cookie token = register("erp@example.com");
        UUID userId = userId("erp@example.com");
        UUID passedId = seedInvoice(userId, "PASSED", "033001900111", "00123456");
        seedInvoice(userId, "REVIEW_REQUIRED", "033001900112", "00123457");
        seedInvoice(userId, "BLOCKED_DUPLICATE", "033001900113", "00123458");

        mvc.perform(get("/api/erp/invoices").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(passedId.toString()))
                .andExpect(jsonPath("$[0].status").value("PASSED"));
    }

    @Test
    void repeatedIdempotencyKeyReturnsOriginalResponseWithoutSecondSideEffect() throws Exception {
        Cookie token = register("erp-push@example.com");
        UUID userId = userId("erp-push@example.com");
        UUID invoiceId = seedInvoice(userId, "PASSED", "033001900211", "00223456");

        MvcResult first = mvc.perform(post("/api/erp/invoices/push")
                        .with(csrf())
                        .cookie(token)
                        .header("Idempotency-Key", "push-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"" + invoiceId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceId").value(invoiceId.toString()))
                .andExpect(jsonPath("$.status").value("PUSHED"))
                .andReturn();

        MvcResult second = mvc.perform(post("/api/erp/invoices/push")
                        .with(csrf())
                        .cookie(token)
                        .header("Idempotency-Key", "push-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"" + invoiceId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceId").value(invoiceId.toString()))
                .andExpect(jsonPath("$.status").value("PUSHED"))
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        Integer receiptCount = jdbc.queryForObject(
                "select count(*) from erp_push_receipts where user_id = ? and idempotency_key = ?",
                Integer.class,
                userId,
                "push-key-1");
        assertThat(receiptCount).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select status from invoices where id = ?", String.class, invoiceId))
                .isEqualTo("PASSED");
    }

    @Test
    void idempotencyKeyLongerThan128CharactersIsRejected() throws Exception {
        Cookie token = register("erp-long-key@example.com");
        UUID userId = userId("erp-long-key@example.com");
        UUID invoiceId = seedInvoice(userId, "PASSED", "033001900311", "00323456");
        String key = "k".repeat(129);

        mvc.perform(post("/api/erp/invoices/push")
                        .with(csrf())
                        .cookie(token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"" + invoiceId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_TOO_LONG"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key must be at most 128 characters"));

        Integer receiptCount = jdbc.queryForObject(
                "select count(*) from erp_push_receipts where user_id = ?",
                Integer.class,
                userId);
        assertThat(receiptCount).isZero();
    }

    @Test
    void concurrentPushWithTheSameKeyCallsTheClientOnce() throws Exception {
        UUID userId = seedUser("erp-race@example.com");
        UUID invoiceId = seedInvoice(userId, "PASSED", "033001900411", "00423456");
        ErpPushRequest request = new ErpPushRequest(invoiceId);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch inPush = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            calls.incrementAndGet();
            inPush.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(pushClient).push(any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            Future<ErpPushResponse> first = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return erpService.push(userId, "race-key", request);
            });
            Future<ErpPushResponse> second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return erpService.push(userId, "race-key", request);
            });
            assertThat(inPush.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);
            assertThat(calls).hasValue(1);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo("PUSHED");
            assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo("PUSHED");
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
            reset(pushClient);
        }

        Integer receiptCount = jdbc.queryForObject(
                "select count(*) from erp_push_receipts where user_id = ? and idempotency_key = ?",
                Integer.class,
                userId,
                "race-key");
        assertThat(receiptCount).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select status from invoices where id = ?", String.class, invoiceId))
                .isEqualTo("PASSED");
    }

    private Cookie register(String email) throws Exception {
        return mvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"StrongPass1!\"}"))
                .andReturn()
                .getResponse()
                .getCookie("access_token");
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private UUID seedUser(String email) {
        registerDirect(email);
        return userId(email);
    }

    private void registerDirect(String email) {
        try {
            mvc.perform(post("/api/auth/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"StrongPass1!\"}"))
                    .andExpect(status().isCreated());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID seedInvoice(UUID userId, String status, String code, String number) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                """
                insert into invoices(
                    id, user_id, invoice_type, invoice_code, invoice_number, invoice_date,
                    amount, tax, total, status, exact_duplicate_key, fields_json, created_at, updated_at)
                values (?, ?, 'VAT_SPECIAL', ?, ?, ?, 100.00, 13.00, 113.00, ?, null, '{}', ?, ?)
                """,
                id,
                userId,
                code,
                number,
                LocalDate.of(2024, 4, 1),
                status,
                now,
                now);
        return id;
    }
}
