package org.pragmatica.kafka;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.pragmatica.lang.Functions.ThrowingRunnable;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

/// Close-once state shared by the three clients. The first close starts its action; every later call gets the same
/// promise, so close is idempotent. Boundedness is the action's: each one ends in a kafka-clients `close(Duration)`,
/// which force-closes at its deadline.
final class Lifecycle {
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Promise<Unit> closed = Promise.promise();
    private final String name;

    private Lifecycle(String name) {
        this.name = name;
    }

    static Lifecycle lifecycle(String name) {
        return new Lifecycle(name);
    }

    boolean isClosing() {
        return closing.get();
    }

    /// Runs a blocking close on a fresh daemon thread: not the caller's, never a Kafka I/O thread.
    Promise<Unit> close(ThrowingRunnable action) {
        return closeAsync(() -> onFreshThread(action));
    }

    Promise<Unit> closeAsync(Supplier<Promise<Unit>> action) {
        return closing.compareAndSet(false, true)
               ? action.get()
                       .onResult(closed::resolve)
               : closed;
    }

    private Promise<Unit> onFreshThread(ThrowingRunnable action) {
        var done = Promise.<Unit>promise();
        Thread.ofPlatform()
              .daemon()
              .name(name + "-closer")
              .start(() -> done.resolve(Result.lift(KafkaError::fromOperation, action)));
        return done;
    }
}
