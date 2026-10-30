package com.acme.invoice.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class PdfTextExtractor {

    private PdfTextExtractor() {
    }

    static List<String> lines(byte[] pdf) {
        if (pdf == null || pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P') {
            return List.of();
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            if (text == null || text.isBlank()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
            return lines;
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
    }
}
