package org.pragmatica.aether.stream;

import org.pragmatica.lang.Contract;

import java.util.List;


/// Callback invoked before events are evicted from the ring buffer.
/// Implementations can capture events for persistent storage (segment sealing).
@FunctionalInterface public interface EvictionListener {
    @Contract void onEviction(String streamName, int partition, List<OffHeapRingBuffer.RawEvent> events);

    EvictionListener NOOP = (_, _, _) -> {};
}
