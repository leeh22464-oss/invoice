package com.acme.invoice.ocr;

import com.acme.invoice.credential.PaddleCredential;
import com.acme.invoice.ingestion.StoredFile;

public interface OcrProvider {

    OcrDocument recognize(PaddleCredential credential, StoredFile file);
}
