package com.acme.invoice.credential;

public record EncryptedSecret(byte[] iv, byte[] ciphertext) {
}
