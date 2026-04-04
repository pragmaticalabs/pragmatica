package org.pragmatica.aether.stream;

import org.pragmatica.aether.stream.StreamConsumerRuntime.ConsumerCallback;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.Deserializer;

import java.util.function.Function;


/// Adapts slice consumer methods to ConsumerCallback for stream subscription.
///
/// Bridges between the raw byte-oriented ring buffer delivery and typed
/// slice method invocations. Handles deserialization and maps the slice
/// method's return value to the ConsumerCallback contract.
///
/// Slice methods return `Promise<Unit>` per spec. The adapter awaits the promise
/// with a timeout to bridge to the synchronous `Result<Unit>` ConsumerCallback contract.
@SuppressWarnings("JBCT-UTIL-02") public interface StreamConsumerAdapter {
    TimeSpan HANDLER_TIMEOUT = TimeSpan.timeSpan(30).seconds();

    static <T> ConsumerCallback singleEvent(Deserializer deserializer, Function<T, Promise<Unit>> handler) {
        return (offset, payload, timestamp) -> invokeHandler(deserializer, handler, payload);
    }

    static <T> StreamConsumerRuntime.BatchConsumerCallback batch(Deserializer deserializer,
                                                                 Function<java.util.List<T>, Promise<Unit>> handler) {
        return events -> invokeBatchHandler(deserializer, handler, events);
    }

    private static <T> Result<Unit> invokeHandler(Deserializer deserializer,
                                                  Function<T, Promise<Unit>> handler,
                                                  byte[] payload) {
        T event = deserializer.decode(payload);
        return handler.apply(event).timeout(HANDLER_TIMEOUT)
                            .await();
    }

    private static <T> Result<Unit> invokeBatchHandler(Deserializer deserializer,
                                                       Function<java.util.List<T>, Promise<Unit>> handler,
                                                       java.util.List<OffHeapRingBuffer.RawEvent> events) {
        var decoded = events.stream().map(raw -> (T) deserializer.<T>decode(raw.data()))
                                   .toList();
        return handler.apply(decoded).timeout(HANDLER_TIMEOUT)
                            .await();
    }
}
