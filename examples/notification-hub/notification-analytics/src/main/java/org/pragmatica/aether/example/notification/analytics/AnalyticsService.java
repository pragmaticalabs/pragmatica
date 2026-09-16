package org.pragmatica.aether.example.notification.analytics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.example.notification.DeliveryStatusEvent;
import org.pragmatica.aether.example.notification.NotificationEvent;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.aether.example.notification.analytics.AnalyticsResponse.analyticsResponse;


@Slice
public interface AnalyticsService {
    @Codec
    record StatsRequest() {}

    @NotificationConsumer
    Promise<Unit> processNotification(NotificationEvent event);

    /// The subscribing end of the example's CO-DEPLOYED TOPIC (#1216). The publisher is the EMAILER
    /// slice — a different artifact in the same blueprint — so this binding only receives anything
    /// if both ends scope the bare name `delivery-status` to the blueprint rather than to
    /// themselves. See `Topics` for why the direction matters.
    @DeliveryStatusSubscription
    Promise<Unit> onDeliveryStatus(DeliveryStatusEvent event);

    Promise<AnalyticsResponse> stats(StatsRequest request);

    static AnalyticsService analyticsService() {
        return new analyticsService(new ConcurrentHashMap<>(), new AtomicLong(), new AtomicLong());
    }

    record analyticsService(ConcurrentHashMap<String, AtomicLong> senderCounts,
                            AtomicLong deliveredCount,
                            AtomicLong failedCount) implements AnalyticsService {
        @Override
        public Promise<Unit> processNotification(NotificationEvent event) {
            senderCounts.computeIfAbsent(event.senderId(), _ -> new AtomicLong()).incrementAndGet();

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> onDeliveryStatus(DeliveryStatusEvent event) {
            if (event.delivered()) {
                deliveredCount.incrementAndGet();
            } else {
                failedCount.incrementAndGet();
            }

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
