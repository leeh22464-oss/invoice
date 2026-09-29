package com.acme.invoice.credential;

public interface SecretCipher {

    EncryptedSecret encrypt(String plaintext);

    String decrypt(EncryptedSecret encrypted);
}
