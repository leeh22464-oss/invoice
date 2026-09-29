package com.acme.invoice.credential;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmSecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsWithoutStoringPlaintext() {
        var cipher = new AesGcmSecretCipher(KEY);
        var encrypted = cipher.encrypt("api-key-sensitive");

        assertThat(cipher.decrypt(encrypted)).isEqualTo("api-key-sensitive");
        assertThat(encrypted.iv()).hasSize(12);
        assertThat(new String(encrypted.ciphertext(), StandardCharsets.UTF_8))
                .doesNotContain("api-key-sensitive");
    }

    @Test
    void repeatedEncryptionUsesFreshRandomIvAndCiphertext() {
        var cipher = new AesGcmSecretCipher(KEY);

        var first = cipher.encrypt("same-secret");
        var second = cipher.encrypt("same-secret");

        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void tamperingIsDetectedWithoutLeakingSecret() {
        var cipher = new AesGcmSecretCipher(KEY);
        var encrypted = cipher.encrypt("never-show-this");
        encrypted.ciphertext()[0] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(encrypted))
                .hasCauseInstanceOf(GeneralSecurityException.class)
                .hasMessageNotContaining("never-show-this");
    }

    @Test
    void rejectsMissingMalformedAndWrongLengthMasterKeys() {
        assertThatThrownBy(() -> new AesGcmSecretCipher(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmSecretCipher("not-base64%%%"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmSecretCipher(Base64.getEncoder().encodeToString(new byte[31])))
                .isInstanceOf(IllegalStateException.class);
    }
}
