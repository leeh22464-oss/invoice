package com.acme.invoice.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadPolicyTest {

    private final UploadPolicy policy = new UploadPolicy();

    @Test
    void acceptsExactlyOneHundredFilesAndRejectsOneHundredOne() {
        policy.validateBatchSize(100);
        assertThatThrownBy(() -> policy.validateBatchSize(101))
                .isInstanceOf(UploadException.class)
                .hasMessageContaining("100");
    }

    @Test
    void acceptsExactSizeAndRejectsOneByteLarger() {
        policy.validateMetadata("invoice.png", "image/png", UploadPolicy.MAX_FILE_BYTES);
        assertThatThrownBy(() -> policy.validateMetadata(
                "invoice.png", "image/png", UploadPolicy.MAX_FILE_BYTES + 1))
                .isInstanceOf(UploadException.class)
                .hasMessageContaining("20 MiB");
    }

    @Test
    void recognizesAllowedMagicOnlyWhenExtensionAndDeclaredTypeMatch() {
        assertThat(policy.detectMediaType("a.jpg", "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01}))
                .isEqualTo("image/jpeg");
        assertThat(policy.detectMediaType("a.jpeg", "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01}))
                .isEqualTo("image/jpeg");
        assertThat(policy.detectMediaType("a.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}))
                .isEqualTo("image/png");
        assertThat(policy.detectMediaType("a.pdf", "application/pdf",
                "%PDF-1.7".getBytes()))
                .isEqualTo("application/pdf");

        assertThatThrownBy(() -> policy.detectMediaType("a.jpg", "image/jpeg",
                "<script>".getBytes()))
                .isInstanceOf(UploadException.class);
        assertThatThrownBy(() -> policy.detectMediaType("a.jpg", "image/png",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}))
                .isInstanceOf(UploadException.class);
        assertThatThrownBy(() -> policy.detectMediaType("a.exe",
                "application/octet-stream", new byte[]{0x4d, 0x5a}))
                .isInstanceOf(UploadException.class);
    }

    @Test
    void acceptsTwentyPagePdfAndRejectsTwentyOne() throws Exception {
        byte[] twenty = pdf(20);
        byte[] twentyOne = pdf(21);
        try (PDDocument document = Loader.loadPDF(twenty)) {
            policy.validatePdfPages(document);
        }
        try (PDDocument document = Loader.loadPDF(twentyOne)) {
            assertThatThrownBy(() -> policy.validatePdfPages(document))
                    .isInstanceOf(UploadException.class)
                    .hasMessageContaining("20 pages");
        }
    }

    private byte[] pdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
