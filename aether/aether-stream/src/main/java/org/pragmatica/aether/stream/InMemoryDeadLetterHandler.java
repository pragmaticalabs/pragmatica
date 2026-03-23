package org.pragmatica.aether.stream;

import org.pragmatica.aether.stream.DeadLetterHandler.DeadLetterEntry;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.pragmatica.lang.Option.option;

/// In-memory implementation of DeadLetterHandler backed by a ConcurrentHashMap.
final class InMemoryDeadLetterHandler implements DeadLetterHandler {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<DeadLetterEntry>> entries = new ConcurrentHashMap<>();

    @SuppressWarnings("JBCT-RET-01") // Side-effect recording; callers use fire-and-forget
    @Override
    public void record(String streamName,
                       int partition,
                       long offset,
                       byte[] payload,
                       String errorMessage,
                       int attemptCount) {
        var entry = DeadLetterEntry.deadLetterEntry(streamName,
                                                     partition,
                                                     offset,
                                                     payload,
                                                     errorMessage,
                                                     attemptCount,
                                                     System.currentTimeMillis());
        entries.computeIfAbsent(streamName, _ -> new CopyOnWriteArrayList<>())
               .add(entry);
    }

    @Override
    public List<DeadLetterEntry> read(String streamName, int maxCount) {
        return option(entries.get(streamName))
            .map(list -> list.stream().limit(maxCount).toList())
            .or(List.of());
    }
}
