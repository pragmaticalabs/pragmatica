package org.pragmatica.aether.stream;

import java.util.List;

/// Callback invoked before events are evicted from the ring buffer.
/// Implementations can capture events for persistent storage (segment sealing).
@FunctionalInterface
public interface EvictionListener {

    /// Called with the batch of events about to be evicted.
    /// The listener should process events synchronously before returning.
    void onEviction(String streamName, int partition, List<OffHeapRingBuffer.RawEvent> events);

    /// No-op listener that discards events (Phase 1 behavior).
    EvictionListener NOOP = (_, _, _) -> {};
}
