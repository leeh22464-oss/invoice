package com.acme.invoice.ocr;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Clock;
import java.time.Duration;

@Configuration
@Profile("!smoke")
public class PaddleClientConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    WebClient.Builder paddleWebClientBuilder() {
        HttpClient client = HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(120));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(client));
    }
}
