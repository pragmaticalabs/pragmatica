package org.pragmatica.aether.example.notification.analytics;

import java.util.Map;

import org.pragmatica.serialization.Codec;


@Codec
public record AnalyticsResponse(Map<String, Long> senderCounts, long totalEvents) {
    public static AnalyticsResponse analyticsResponse(Map<String, Long> senderCounts, long totalEvents) {
        return new AnalyticsResponse(senderCounts, totalEvents);
    }
}
