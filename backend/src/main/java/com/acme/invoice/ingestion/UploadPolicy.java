package com.acme.invoice.ingestion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

@Component
public class UploadPolicy {

    public static final int MAX_FILES = 100;
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_PDF_PAGES = 20;

    private static final Map<String, String> EXTENSION_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "pdf", "application/pdf");

    public void validateBatchSize(int count) {
        if (count < 1 || count > MAX_FILES) {
            throw new UploadException(
                    "INVALID_FILE_COUNT", "A batch must contain between 1 and 100 files");
        }
    }

    public void validateMetadata(String originalName, String declaredType, long size) {
        if (size < 0 || size > MAX_FILE_BYTES) {
            throw new UploadException(
                    "FILE_TOO_LARGE", "Each file must be at most 20 MiB");
        }
        if (originalName == null || originalName.length() > 255) {
            throw new UploadException(
                    "INVALID_FILENAME", "Filename must contain at most 255 characters");
        }
        String expected = expectedMediaType(originalName);
        if (!expected.equals(normalizeType(declaredType))) {
            throw new UploadException(
                    "MEDIA_TYPE_MISMATCH",
                    "Filename extension and declared media type must match");
        }
    }

    public String detectMediaType(
            String originalName, String declaredType, byte[] prefix) {
        String expected = expectedMediaType(originalName);
        if (!expected.equals(normalizeType(declaredType))) {
            throw new UploadException(
                    "MEDIA_TYPE_MISMATCH",
                    "Filename extension and declared media type must match");
        }
        String actual = actualMediaType(prefix);
        if (!expected.equals(actual)) {
            throw new UploadException(
                    "INVALID_FILE_SIGNATURE",
                    "File signature does not match its filename and media type");
        }
        return actual;
    }

    public String expectedMediaType(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new UploadException("INVALID_FILENAME", "A filename is required");
        }
        int dot = originalName.lastIndexOf('.');
        String extension = dot < 0 ? "" :
                originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = EXTENSION_TYPES.get(extension);
        if (type == null) {
            throw new UploadException(
                    "UNSUPPORTED_FILE_TYPE", "Only JPEG, PNG, and PDF files are supported");
        }
        return type;
    }

    public void validatePdfPages(PDDocument document) {
        if (document.getNumberOfPages() > MAX_PDF_PAGES) {
            throw new UploadException(
                    "PDF_TOO_MANY_PAGES", "PDF files must contain at most 20 pages");
        }
    }

    private String actualMediaType(byte[] prefix) {
        if (startsWith(prefix, new byte[]{
                (byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return "image/jpeg";
        }
        if (startsWith(prefix, new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return "image/png";
        }
        if (startsWith(prefix, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        throw new UploadException(
                "UNSUPPORTED_FILE_TYPE", "File content is not a supported JPEG, PNG, or PDF");
    }

    private boolean startsWith(byte[] bytes, byte[] signature) {
        return bytes.length >= signature.length
                && Arrays.equals(
                        Arrays.copyOf(bytes, signature.length), signature);
    }

    private String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        int semicolon = type.indexOf(';');
        return (semicolon < 0 ? type : type.substring(0, semicolon))
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
