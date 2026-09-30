package com.acme.invoice.ocr;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!smoke")
public class StoredFileContentHolder {

    private final ThreadLocal<byte[]> content = new ThreadLocal<>();

    public void set(byte[] bytes) {
        content.set(bytes);
    }

    public byte[] take() {
        byte[] bytes = content.get();
        content.remove();
        return bytes;
    }
}
