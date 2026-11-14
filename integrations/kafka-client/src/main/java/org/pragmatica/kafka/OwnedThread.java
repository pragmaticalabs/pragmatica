package org.pragmatica.kafka;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// One platform thread that owns a non-thread-safe resource (the Kafka consumer). Tasks run in submission order; the
/// thread is the confinement boundary and `isOwner` is its guard.
final class OwnedThread {
    private final ExecutorService executor;
    private volatile Thread owner;

    private OwnedThread(String name) {
        this.executor = Executors.newSingleThreadExecutor(runnable -> start(name, runnable));
    }

    static OwnedThread ownedThread(String name) {
        return new OwnedThread(name);
    }

    private Thread start(String name, Runnable runnable) {
        owner = Thread.ofPlatform().daemon().name(name).unstarted(runnable);

        return owner;
    }

    boolean isOwner() {
        return Thread.currentThread() == owner;
    }

    /// Runs `task` on the owned thread. After [#shutdown] it refuses with [KafkaError.General#CLOSED].
    <T> Promise<T> submit(Supplier<Result<T>> task) {
        var promise = Promise.<T> promise();

        try {
            executor.execute(() -> promise.resolve(task.get()));

            return promise;
        } catch (RejectedExecutionException e) {
            return KafkaError.General.CLOSED.promise();
        }
    }

    /// Lets already queued tasks finish, then ends the thread.
    @Contract
    void shutdown() {
        executor.shutdown();
    }
}
