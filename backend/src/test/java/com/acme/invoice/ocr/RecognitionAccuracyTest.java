package com.acme.invoice.ocr;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecognitionAccuracyTest {

    private final InvoiceNormalizer normalizer = new InvoiceNormalizer();

    @Test
    void keepsTheTwentyDigitNumberOutOfTheInvoiceCode() {
        NormalizedInvoice invoice = normalizer.normalize(PaddleOcrV5Parser.fromLines(List.of(
                "电子发票（普通发票）",
                "发票代码：26442000004592117371",
                "发票号码：",
                "电子客票号：",
                "开票日期：2026年04月27日",
                "合计金额：149.50",
                "合计税额：1.50",
                "价税合计：壹佰伍拾壹圆整"
        ), "{}"));

        assertThat(invoice.type()).isEqualTo(InvoiceType.ELECTRONIC);
        assertThat(invoice.invoiceCode()).isNull();
        assertThat(invoice.invoiceNumber()).isEqualTo("26442000004592117371");
        assertThat(invoice.invoiceDate()).isEqualTo(LocalDate.of(2026, 4, 27));
        assertThat(invoice.amount()).isEqualByComparingTo("149.50");
        assertThat(invoice.tax()).isEqualByComparingTo("1.50");
        assertThat(invoice.total()).isEqualByComparingTo("151.00");
    }

    @Test
    void readsARailwayDatePrintedBesideTheInvoiceNumber() {
        NormalizedInvoice invoice = normalizer.normalize(PaddleOcrV5Parser.fromLines(List.of(
                "电子发票（铁路电子客票）",
                "发票号码:26939133284002962213 开票日期:2026年09月01日",
                "票价:77.00",
                "电子客票号:3328459086092198074182026"
        ), "{\"source\":\"pdf-text\"}"));

        assertThat(invoice.type()).isEqualTo(InvoiceType.RAILWAY);
        assertThat(invoice.invoiceNumber()).isEqualTo("26939133284002962213");
        assertThat(invoice.fields().get("ticket_num").sourceText()).isEqualTo("3328459086092198074182026");
        assertThat(invoice.invoiceDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(invoice.total()).isEqualByComparingTo("77.00");
    }

    @Test
    void readsStructuredModelJson() {
        BaiduPaddleOcrProvider provider = new BaiduPaddleOcrProvider(null, file -> null, java.time.Duration.ofSeconds(1), ignored -> {
        });
        OcrDocument document = provider.parseChatResponse("""
                {"id":"1","choices":[{"message":{"content":"{\\"title\\":\\"电子发票（普通发票）\\",\\"invoice_code\\":\\"\\",\\"invoice_number\\":\\"26932000000640107571\\",\\"ticket_number\\":\\"\\",\\"date\\":\\"2026-04-24\\",\\"amount\\":\\"196.04\\",\\"tax\\":\\"1.96\\",\\"total\\":\\"198.00\\"}"}}]}
                """);

        NormalizedInvoice invoice = normalizer.normalize(document);

        assertThat(invoice.invoiceCode()).isNull();
        assertThat(invoice.invoiceNumber()).isEqualTo("26932000000640107571");
        assertThat(invoice.total()).isEqualByComparingTo(new BigDecimal("198.00"));
    }

    @Test
    void parsesChineseUppercaseTotals() {
        assertThat(ChineseAmount.parse("壹佰伍拾壹圆整")).isEqualByComparingTo("151.00");
        assertThat(ChineseAmount.parse("壹佰肆拾玖元伍角")).isEqualByComparingTo("149.50");
    }
}
