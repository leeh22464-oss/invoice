package com.acme.invoice.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Configuration
@Profile("!smoke")
public class OcrProviderConfiguration {

    @Bean
    OcrProvider baiduPaddleOcrProvider(
            PaddleTokenClient tokenClient,
            WebClient.Builder builder,
            StoredFileContentHolder contentHolder,
            @Value("${app.paddle.recognize-url:https://aip.baidubce.com/rest/2.0/ocr/v1/multiple_invoice}")
            String recognizeUrl,
            @Value("${app.paddle.timeout-ms:10000}") int timeoutMillis) {
        return new BaiduPaddleOcrProvider(
                tokenClient,
                builder,
                recognizeUrl,
                file -> {
                    byte[] bytes = contentHolder.take();
                    if (bytes == null) {
                        throw new IllegalStateException("OCR file content was not prepared");
                    }
                    return bytes;
                },
                Duration.ofMillis(timeoutMillis),
                duration -> {
                    try {
                        Thread.sleep(Math.max(1L, duration.toMillis()));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new PaddleServiceUnavailableException();
                    }
                });
    }
}
