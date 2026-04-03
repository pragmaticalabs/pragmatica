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


@Slice public interface AnalyticsService {
    @Codec record StatsRequest(){}

    @NotificationConsumer Promise<Unit> processNotification(NotificationEvent event);
    Promise<AnalyticsResponse> stats(StatsRequest request);

    static AnalyticsService analyticsService() {
        return new analyticsService(new ConcurrentHashMap<>());
    }

    record analyticsService(ConcurrentHashMap<String, AtomicLong> senderCounts) implements AnalyticsService {
        @Override public Promise<Unit> processNotification(NotificationEvent event) {
            senderCounts.computeIfAbsent(event.senderId(), _ -> new AtomicLong()).incrementAndGet();
            return Promise.success(Unit.unit());
        }

        @Override public Promise<AnalyticsResponse> stats(StatsRequest request) {
            return Promise.success(analyticsResponse(buildCountSnapshot(), computeTotal()));
        }

        private Map<String, Long> buildCountSnapshot() {
            Map<String, Long> snapshot = new HashMap<>();
            senderCounts.forEach((key, counter) -> snapshot.put(key, counter.get()));
            return Map.copyOf(snapshot);
        }

        private long computeTotal() {
            return senderCounts.values().stream()
                                      .mapToLong(AtomicLong::get)
                                      .sum();
        }
    }
}
