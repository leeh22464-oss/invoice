package com.acme.invoice.ingestion;

import java.io.InputStream;

record PreviewFile(
        InputStream content,
        String mediaType,
        long size,
        String displayName
) {
}
