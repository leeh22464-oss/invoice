package com.acme.invoice.ingestion;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TomcatMultipartLimitTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Path STORAGE_ROOT;

    static {
        POSTGRES.start();
        try {
            STORAGE_ROOT = Files.createTempDirectory("invoice-tomcat-upload-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
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
        registry.add("app.storage.root", STORAGE_ROOT::toString);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ServletWebServerApplicationContext serverContext;

    @BeforeEach
    void clean() {
        jdbc.update("delete from invoice_files");
        jdbc.update("delete from import_batches");
        jdbc.update("delete from users");
    }

    @Test
    void tomcatMaxPartCountIsAtLeastOneHundredTen() {
        TomcatWebServer webServer = (TomcatWebServer) serverContext.getWebServer();
        Connector connector = webServer.getTomcat().getConnector();
        assertThat(connector.getMaxPartCount()).isGreaterThanOrEqualTo(110);
    }

    @Test
    void acceptsOneHundredFilesThroughRealTomcatMultipartParser() {
        String sessionCookies = register("tomcat-parts@example.com");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        for (int i = 0; i < 100; i++) {
            body.add("files", pngResource("file-" + i + ".png"));
        }
        String csrf = extractCookie(sessionCookies, "XSRF-TOKEN");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add(HttpHeaders.COOKIE, sessionCookies);
        headers.add("X-XSRF-TOKEN", csrf);

        ResponseEntity<String> response = rest.postForEntity(
                url("/api/import-batches"),
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("real Tomcat multipart parse must accept 100 files when max-part-count >= 110")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"totalFiles\":100");
        assertThat(jdbc.queryForObject("select count(*) from import_batches", Integer.class))
                .isEqualTo(1);
    }

    private String register(String email) {
        String csrf = UUID.randomUUID().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf);
        headers.add("X-XSRF-TOKEN", csrf);
        ResponseEntity<String> response = rest.postForEntity(
                url("/api/auth/register"),
                new HttpEntity<>(
                        "{\"email\":\"" + email + "\",\"password\":\"StrongPass1!\"}",
                        headers),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull();
        String access = setCookies.stream()
                .filter(value -> value.startsWith("access_token="))
                .map(value -> value.split(";", 2)[0])
                .findFirst()
                .orElseThrow();
        String xsrf = setCookies.stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .map(value -> value.split(";", 2)[0])
                .findFirst()
                .orElse("XSRF-TOKEN=" + csrf);
        return access + "; " + xsrf;
    }

    private String extractCookie(String cookies, String name) {
        for (String part : cookies.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        throw new IllegalStateException("Missing cookie " + name);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ByteArrayResource pngResource(String filename) {
        byte[] bytes = pngBytes(64);
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private byte[] pngBytes(int size) {
        byte[] bytes = new byte[size];
        byte[] signature = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return bytes;
    }
}
