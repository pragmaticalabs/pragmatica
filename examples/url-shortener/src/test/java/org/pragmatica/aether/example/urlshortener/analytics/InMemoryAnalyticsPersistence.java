package org.pragmatica.aether.example.urlshortener.analytics;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// In-memory AnalyticsPersistence implementation for testing.
final class InMemoryAnalyticsPersistence implements AnalyticsPersistence {
    private final Map<String, AtomicLong> clickCounts = new ConcurrentHashMap<>();

    static InMemoryAnalyticsPersistence inMemoryAnalyticsPersistence() {
        return new InMemoryAnalyticsPersistence();
    }

    @Override
    public Promise<Unit> insertClick(String shortCode) {
        clickCounts.computeIfAbsent(shortCode, _ -> new AtomicLong(0)).incrementAndGet();
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Long> countByShortCode(String shortCode) {
        return Promise.success(clickCounts.computeIfAbsent(shortCode, _ -> new AtomicLong(0)).get());
    }
}
