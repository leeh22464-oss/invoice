package com.acme.invoice.ingestion;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingCleanupRegistry {

    public record Entry(UUID userId, String storageKey) {
        public Entry {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(storageKey, "storageKey");
        }
    }

    private final Set<Entry> pending = ConcurrentHashMap.newKeySet();

    public void register(UUID userId, String storageKey) {
        pending.add(new Entry(userId, storageKey));
    }

    public void remove(UUID userId, String storageKey) {
        pending.remove(new Entry(userId, storageKey));
    }

    public boolean contains(UUID userId, String storageKey) {
        return pending.contains(new Entry(userId, storageKey));
    }

    public int size() {
        return pending.size();
    }

    public Set<Entry> snapshot() {
        return Set.copyOf(pending);
    }

    public void clear() {
        pending.clear();
    }
}
