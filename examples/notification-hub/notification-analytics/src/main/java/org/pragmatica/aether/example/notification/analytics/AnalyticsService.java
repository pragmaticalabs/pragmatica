package org.pragmatica.aether.example.notification.analytics;

import org.pragmatica.aether.example.notification.NotificationEvent;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Codec;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.aether.example.notification.analytics.AnalyticsResponse.analyticsResponse;

/// Analytics slice - counts notifications per sender.
///
/// Subscribes to the notification event stream and maintains
/// per-sender counters in memory.
@Slice
public interface AnalyticsService {
    // === Requests ===
    @Codec
    record StatsRequest() {}

    // === Operations ===
    /// Stream consumer - counts notifications per sender.
    @NotificationConsumer
    Promise<Unit> processNotification(NotificationEvent event);

    /// Get analytics (public).
    Promise<AnalyticsResponse> stats(StatsRequest request);

    // === Factory ===
    /// Factory method.
    static AnalyticsService analyticsService() {
        return new analyticsService(new ConcurrentHashMap<>());
    }

    record analyticsService(ConcurrentHashMap<String, AtomicLong> senderCounts) implements AnalyticsService {
        @Override
        public Promise<Unit> processNotification(NotificationEvent event) {
            senderCounts.computeIfAbsent(event.senderId(),
                                         _ -> new AtomicLong())
                        .incrementAndGet();
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<AnalyticsResponse> stats(StatsRequest request) {
            return Promise.success(analyticsResponse(buildCountSnapshot(), computeTotal()));
        }

        private Map<String, Long> buildCountSnapshot() {
            Map<String, Long> snapshot = new HashMap<>();
            senderCounts.forEach((key, counter) -> snapshot.put(key, counter.get()));
            return Map.copyOf(snapshot);
        }

        private long computeTotal() {
            return senderCounts.values()
                               .stream()
                               .mapToLong(AtomicLong::get)
                               .sum();
        }
    }
}
