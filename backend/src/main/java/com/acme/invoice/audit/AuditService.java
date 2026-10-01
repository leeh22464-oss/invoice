package com.acme.invoice.audit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

@Service
@Profile("!smoke")
public class AuditService {

    private final AuditEventRepository repository;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void recordCorrection(
            UUID userId,
            UUID invoiceId,
            String fieldName,
            String oldValue,
            String newValue) {
        repository.save(new AuditEvent(
                UUID.randomUUID(),
                userId,
                invoiceId,
                fieldName,
                oldValue,
                newValue,
                clock.instant()));
    }
}
