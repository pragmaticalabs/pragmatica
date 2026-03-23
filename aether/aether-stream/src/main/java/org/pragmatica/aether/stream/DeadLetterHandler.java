package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/// Handles dead-letter storage for events that failed processing.
///
/// When RETRY exhausts retries or SKIP drops an event, the failed event is recorded
/// here with metadata for later inspection or reprocessing.
public interface DeadLetterHandler {

    /// Record a dead-letter event.
    void record(String streamName, int partition, long offset, byte[] payload,
                String errorMessage, int attemptCount);

    /// Read dead-letter events for a stream, up to maxCount.
    List<DeadLetterEntry> read(String streamName, int maxCount);

    /// Dead-letter entry record.
    record DeadLetterEntry(String streamName, int partition, long offset, byte[] payload,
                           String errorMessage, int attemptCount, long timestamp) {

        public static DeadLetterEntry deadLetterEntry(String streamName, int partition, long offset,
                                                      byte[] payload, String errorMessage,
                                                      int attemptCount, long timestamp) {
            return new DeadLetterEntry(streamName, partition, offset, payload, errorMessage, attemptCount, timestamp);
        }
    }

    /// Create a new in-memory dead-letter handler.
    static DeadLetterHandler deadLetterHandler() {
        var entries = new ConcurrentHashMap<String, CopyOnWriteArrayList<DeadLetterEntry>>();

        return new DeadLetterHandler() {
            @Override
            public void record(String streamName, int partition, long offset, byte[] payload,
                               String errorMessage, int attemptCount) {
                var entry = DeadLetterEntry.deadLetterEntry(
                    streamName, partition, offset, payload, errorMessage,
                    attemptCount, System.currentTimeMillis()
                );
                entries.computeIfAbsent(streamName, _ -> new CopyOnWriteArrayList<>()).add(entry);
            }

            @Override
            public List<DeadLetterEntry> read(String streamName, int maxCount) {
                var list = entries.get(streamName);
                if (list == null) {
                    return List.of();
                }
                return list.stream().limit(maxCount).toList();
            }
        };
    }
}
