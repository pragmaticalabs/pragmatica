package org.pragmatica.aether.example.notification.analytics;

import org.pragmatica.serialization.Codec;

import java.util.Map;
@Codec public record AnalyticsResponse(Map<String, Long> senderCounts, long totalEvents){
    public static AnalyticsResponse analyticsResponse(Map<String, Long> senderCounts, long totalEvents){
        return new AnalyticsResponse(senderCounts, totalEvents);
    }
}
