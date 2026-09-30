package com.acme.invoice.credential;

import com.acme.invoice.ocr.PaddleTokenClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OcrCredentialServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final OcrCredentialPersistence persistence = mock(OcrCredentialPersistence.class);
    private final SecretCipher cipher = mock(SecretCipher.class);
    private final PaddleTokenClient tokenClient = mock(PaddleTokenClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);
    private OcrCredentialService service;

    @BeforeEach
    void setUp() {
        service = new OcrCredentialService(persistence, cipher, tokenClient, clock);
    }

    @Test
    void masksLongAndShortApiKeys() {
        assertThat(OcrCredentialService.mask("12345678")).isEqualTo("1234****5678");
        assertThat(OcrCredentialService.mask("short")).isEqualTo("****");
    }

    @Test
    void validUpdateTrimsEncryptsIndependentlyAndScopesLookupToOwner() {
        when(tokenClient.validate("api-key-1234", "secret-value"))
                .thenReturn(new CredentialValidation(true, "Credentials are valid"));
        when(cipher.encrypt("api-key-1234")).thenReturn(new EncryptedSecret(new byte[]{1}, new byte[]{2}));
        when(cipher.encrypt("secret-value")).thenReturn(new EncryptedSecret(new byte[]{3}, new byte[]{4}));
        when(persistence.upsert(eq(userId), any(), any(), eq("api-****1234"), eq(clock.instant())))
                .thenAnswer(invocation -> new OcrCredential(
                        UUID.randomUUID(),
                        userId,
                        invocation.<EncryptedSecret>getArgument(1).iv(),
                        invocation.<EncryptedSecret>getArgument(1).ciphertext(),
                        invocation.<EncryptedSecret>getArgument(2).iv(),
                        invocation.<EncryptedSecret>getArgument(2).ciphertext(),
                        invocation.getArgument(3),
                        "VALID",
                        clock.instant(),
                        clock.instant(),
                        clock.instant()));

        var response = service.save(userId,
                new CredentialRequest(" api-key-1234 ", " secret-value "));

        assertThat(response.apiKeyMask()).isEqualTo("api-****1234");
        assertThat(response.validationStatus()).isEqualTo("VALID");
        var apiKey = ArgumentCaptor.forClass(EncryptedSecret.class);
        var secretKey = ArgumentCaptor.forClass(EncryptedSecret.class);
        verify(persistence).upsert(
                eq(userId), apiKey.capture(), secretKey.capture(), eq("api-****1234"), eq(clock.instant()));
        assertThat(apiKey.getValue().iv()).containsExactly(1);
        assertThat(secretKey.getValue().iv()).containsExactly(3);
        assertThat(apiKey.getValue().ciphertext()).containsExactly(2);
        assertThat(secretKey.getValue().ciphertext()).containsExactly(4);
    }

    @Test
    void invalidUpdateDoesNotReplaceExistingCredential() {
        when(tokenClient.validate("bad-api", "bad-secret"))
                .thenReturn(new CredentialValidation(false, "Credentials are invalid"));

        assertThatThrownBy(() -> service.save(userId,
                new CredentialRequest("bad-api", "bad-secret")))
                .isInstanceOf(InvalidCredentialException.class);

        verify(persistence, never()).upsert(any(), any(), any(), any(), any());
        verify(cipher, never()).encrypt(any());
    }

    @Test
    void testsSavedCredentialAndUpdatesValidationTimestamp() {
        var existing = credential(userId);
        when(persistence.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(cipher.decrypt(any())).thenReturn("stored-api", "stored-secret");
        when(tokenClient.validate("stored-api", "stored-secret"))
                .thenReturn(new CredentialValidation(false, "Credentials are invalid"));
        when(persistence.recordValidation(userId, false, clock.instant())).thenAnswer(invocation -> {
            existing.recordValidation(false, clock.instant());
            return existing;
        });

        var response = service.test(userId);

        assertThat(response.validationStatus()).isEqualTo("INVALID");
        assertThat(response.lastValidatedAt()).isEqualTo(clock.instant());
        verify(persistence).findByUserId(userId);
    }

    @Test
    void statusAndDeleteAreOwnerScopedAndDeleteIsIdempotent() {
        when(persistence.findByUserId(userId)).thenReturn(Optional.empty());

        assertThat(service.status(userId).configured()).isFalse();
        service.delete(userId);
        service.delete(userId);

        verify(persistence).findByUserId(userId);
        verify(persistence, times(2)).deleteByUserId(userId);
    }

    private OcrCredential credential(UUID owner) {
        return new OcrCredential(UUID.randomUUID(), owner, new byte[]{1}, new byte[]{2},
                new byte[]{3}, new byte[]{4}, "stor****-api", "VALID",
                clock.instant(), clock.instant(), clock.instant());
    }
}
