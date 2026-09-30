package com.acme.invoice.verification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.math.BigDecimal;
import java.time.Clock;

@Configuration
@Profile("!smoke")
public class VerificationConfiguration {

    @Bean
    DateRangeRule dateRangeRule(
            Clock clock,
            @Value("${app.verification.max-age-years:10}") int maxAgeYears) {
        return new DateRangeRule(clock, maxAgeYears);
    }

    @Bean
    ConfidenceRule confidenceRule(
            @Value("${app.verification.confidence-threshold:0.80}") BigDecimal threshold) {
        return new ConfidenceRule(threshold);
    }
}
