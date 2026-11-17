package org.pragmatica.kafka;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// One platform thread that owns a non-thread-safe resource (the Kafka consumer). Tasks run in submission order; the
/// thread is the confinement boundary and `isOwner` is its guard. The queue of waiting tasks is bounded: a submission
/// past the bound is refused at once with a typed [KafkaError.Retriable] (origin
/// [KafkaError.General#SUBMISSION_QUEUE_FULL]); the caller is never blocked and nothing is dropped silently.
final class OwnedThread {
    /// Waiting-task bound (marked guess): large enough to ride out a slow `poll` or a metadata wait, small enough that
    /// a stuck broker turns into refusals instead of unbounded heap growth.
    static final int DEFAULT_CAPACITY = 10_000;

    private final ThreadPoolExecutor executor;
    private volatile Thread owner;

    private OwnedThread(String name, int capacity) {
        this.executor = new ThreadPoolExecutor(1,
                                               1,
                                               0L,
                                               TimeUnit.MILLISECONDS,
                                               new ArrayBlockingQueue<>(capacity),
                                               runnable -> start(name, runnable));
    }

    static OwnedThread ownedThread(String name) {
        return ownedThread(name, DEFAULT_CAPACITY);
    }

    static OwnedThread ownedThread(String name, int capacity) {
        return new OwnedThread(name, capacity);
    }

    private Thread start(String name, Runnable runnable) {
        owner = Thread.ofPlatform().daemon().name(name).unstarted(runnable);

        return owner;
    }

    boolean isOwner() {
        return Thread.currentThread() == owner;
    }

    /// Runs `task` on the owned thread. After [#shutdown] it refuses with [KafkaError.General#CLOSED]; with the queue
    /// full it refuses with [KafkaError.Retriable] (the task did not run, repeating is safe).
    <T> Promise<T> submit(Supplier<Result<T>> task) {
        var promise = Promise.<T> promise();

        try {
            executor.execute(() -> promise.resolve(task.get()));

            return promise;
        } catch (RejectedExecutionException e) {
            return refusal().promise();
        }
    }

    private KafkaError refusal() {
        return executor.isShutdown()
               ? KafkaError.General.CLOSED
               : KafkaError.Retriable.FACTORY.apply(KafkaError.General.SUBMISSION_QUEUE_FULL);
    }

    /// Lets already queued tasks finish, then ends the thread.
    @Contract
    void shutdown() {
        executor.shutdown();
    }
}
