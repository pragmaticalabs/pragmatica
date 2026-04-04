package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.pragmatica.aether.stream.replication.PartitionKey.partitionKey;
import static org.pragmatica.lang.Option.option;


/// Tracks the latest offset written to each partition on the local node.
/// Used during governor failover to determine catch-up ranges:
/// the new governor reads watermarks from replicas and replays
/// missing events from AHSE segments.
public sealed interface WatermarkTracker {
    @Contract void advance(String streamName, int partition, long offset);
    Option<Long> watermark(String streamName, int partition);
    Map<String, Map<Integer, Long>> allWatermarks();

    static WatermarkTracker watermarkTracker() {
        return new DefaultWatermarkTracker();
    }

    record unused() implements WatermarkTracker {
        @Contract@Override public void advance(String streamName, int partition, long offset) {}

        @Override public Option<Long> watermark(String streamName, int partition) {
            return Option.none();
        }

        @Override public Map<String, Map<Integer, Long>> allWatermarks() {
            return Map.of();
        }
    }
}

final class DefaultWatermarkTracker implements WatermarkTracker {
    private final ConcurrentHashMap<PartitionKey, Long> watermarks = new ConcurrentHashMap<>();

    @Contract@Override public void advance(String streamName, int partition, long offset) {
        var key = partitionKey(streamName, partition);
        watermarks.merge(key, offset, Math::max);
    }

    @Override public Option<Long> watermark(String streamName, int partition) {
        return option(watermarks.get(partitionKey(streamName, partition)));
    }

    @Override public Map<String, Map<Integer, Long>> allWatermarks() {
        return watermarks.entrySet().stream()
                                  .collect(Collectors.groupingBy(e -> e.getKey().streamName(),
                                                                 Collectors.toMap(e -> e.getKey().partition(),
                                                                                  Map.Entry::getValue)));
    }
}
