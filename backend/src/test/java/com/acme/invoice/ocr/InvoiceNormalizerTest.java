package com.acme.invoice.ocr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceNormalizerTest {

    private InvoiceNormalizer normalizer;
    private BaiduPaddleOcrProvider parser;

    @BeforeEach
    void setUp() {
        normalizer = new InvoiceNormalizer();
        parser = new BaiduPaddleOcrProvider(
                null,
                null,
                "unused",
                file -> new byte[0],
                java.time.Duration.ZERO,
                ignored -> {
                });
    }

    @ParameterizedTest
    @CsvSource({
            "vat_special.json,VAT_SPECIAL,033001900111,00123456,2024-01-15,100.00,13.00,113.00",
            "vat_ordinary.json,VAT_ORDINARY,044001800222,00098765,2024-02-20,200.00,6.00,206.00",
            "electronic.json,ELECTRONIC,,24312000000000001234,2024-03-08,88.50,5.31,93.81",
            "railway.json,RAILWAY,,00001234,2024-04-10,143.58,12.92,156.50",
            "flight.json,FLIGHT,,00005555,2024-05-12,1200.00,108.00,1380.00",
            "taxi.json,TAXI,111001234567,00001234,2024-06-01,35.00,,38.00",
            "fixed_amount.json,FIXED_AMOUNT,132081490320,01275486,,,,100.00",
            "customs_payment.json,CUSTOMS_PAYMENT,000000000000,00000001,2024-07-18,5000.00,650.00,5650.00"
    })
    void normalizesEightInvoiceTypesFromFixtures(
            String fixture,
            InvoiceType type,
            String code,
            String number,
            String date,
            String amount,
            String tax,
            String total) throws Exception {
        String rawJson;
        try (InputStream in = fixtureStream(fixture)) {
            rawJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        OcrDocument document = parser.parseResponse(rawJson);

        NormalizedInvoice invoice = normalizer.normalize(document);

        assertThat(invoice.type()).isEqualTo(type);
        assertThat(invoice.invoiceCode()).isEqualTo(blankToNull(code));
        assertThat(invoice.invoiceNumber()).isEqualTo(blankToNull(number));
        assertThat(invoice.invoiceDate())
                .isEqualTo(date == null || date.isBlank() ? null : LocalDate.parse(date));
        assertDecimal(invoice.amount(), amount);
        assertDecimal(invoice.tax(), tax);
        assertDecimal(invoice.total(), total);
        assertThat(invoice.fields()).isNotEmpty();
        assertThat(invoice.fields().values())
                .allSatisfy(field -> {
                    assertThat(field.sourceText()).isNotBlank();
                    assertThat(field.confidence()).isNotNull();
                });
        if (number != null && !number.isBlank() && number.startsWith("0")) {
            assertThat(invoice.invoiceNumber()).startsWith("0");
        }
        if (code != null && !code.isBlank() && code.startsWith("0")) {
            assertThat(invoice.invoiceCode()).startsWith("0");
        }
    }

    private static InputStream fixtureStream(String name) {
        InputStream stream = InvoiceNormalizerTest.class.getResourceAsStream("/ocr/fixtures/" + name);
        assertThat(stream).as("fixture %s", name).isNotNull();
        return stream;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void assertDecimal(BigDecimal actual, String expected) {
        BigDecimal value = decimal(expected);
        if (value == null) {
            assertThat(actual).isNull();
        } else {
            assertThat(actual).isEqualByComparingTo(value);
        }
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }
}
