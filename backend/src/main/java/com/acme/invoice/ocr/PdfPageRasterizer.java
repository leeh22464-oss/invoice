package com.acme.invoice.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class PdfPageRasterizer {

    private static final int MAX_PAGES = 4;
    private static final float DPI = 120f;

    private PdfPageRasterizer() {
    }

    static List<byte[]> pngPages(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            throw new PaddleServiceUnavailableException();
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int count = Math.min(document.getNumberOfPages(), MAX_PAGES);
            List<byte[]> pages = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                BufferedImage image = renderer.renderImageWithDPI(index, DPI, ImageType.RGB);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "png", output)) {
                    throw new PaddleServiceUnavailableException();
                }
                pages.add(output.toByteArray());
            }
            if (pages.isEmpty()) {
                throw new PaddleServiceUnavailableException();
            }
            return pages;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof PaddleServiceUnavailableException unavailable) {
                throw unavailable;
            }
            throw new PaddleServiceUnavailableException();
        }
    }
}
