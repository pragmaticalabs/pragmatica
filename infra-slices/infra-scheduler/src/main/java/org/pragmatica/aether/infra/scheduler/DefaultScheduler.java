package org.pragmatica.aether.infra.scheduler;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of Scheduler using ScheduledExecutorService.
 */
final class DefaultScheduler implements Scheduler {
    private static final Logger log = LoggerFactory.getLogger(DefaultScheduler.class);

    private final ScheduledExecutorService executor;
    private final Map<String, TaskEntry> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    DefaultScheduler() {
        this.executor = Executors.newScheduledThreadPool(Runtime.getRuntime()
                                                                .availableProcessors(),
                                                         r -> {
                                                             var thread = new Thread(r, "scheduler-worker");
                                                             thread.setDaemon(true);
                                                             return thread;
                                                         });
    }

    @Override
    public Promise<Unit> start() {
        running.set(true);
        log.info("Scheduler started");
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Unit> stop() {
        running.set(false);
        tasks.values()
             .forEach(entry -> entry.cancel());
        tasks.clear();
        executor.shutdown();
        try{
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread()
                  .interrupt();
        }
        log.info("Scheduler stopped");
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<ScheduledTaskHandle> scheduleAtFixedRate(String name,
                                                            TimeSpan initialDelay,
                                                            TimeSpan period,
                                                            Fn0<Promise<Unit>> task) {
        if (tasks.containsKey(name)) {
            return new SchedulerError.SchedulingFailed(name, "Task already exists").promise();
        }
        var future = executor.scheduleAtFixedRate(() -> executeTask(name, task),
                                                  initialDelay.millis(),
                                                  period.millis(),
                                                  TimeUnit.MILLISECONDS);
        var entry = new TaskEntry(name, future);
        tasks.put(name, entry);
        log.debug("Scheduled task '{}' at fixed rate: {}ms", name, period.millis());
        return Promise.success(entry);
    }

    @Override
    public Promise<ScheduledTaskHandle> scheduleWithFixedDelay(String name,
                                                               TimeSpan initialDelay,
                                                               TimeSpan delay,
                                                               Fn0<Promise<Unit>> task) {
        if (tasks.containsKey(name)) {
            return new SchedulerError.SchedulingFailed(name, "Task already exists").promise();
        }
        var future = executor.scheduleWithFixedDelay(() -> executeTaskAndWait(name, task),
                                                     initialDelay.millis(),
                                                     delay.millis(),
                                                     TimeUnit.MILLISECONDS);
        var entry = new TaskEntry(name, future);
        tasks.put(name, entry);
        log.debug("Scheduled task '{}' with fixed delay: {}ms", name, delay.millis());
        return Promise.success(entry);
    }

    @Override
    public Promise<ScheduledTaskHandle> schedule(String name, TimeSpan delay, Fn0<Promise<Unit>> task) {
        if (tasks.containsKey(name)) {
            return new SchedulerError.SchedulingFailed(name, "Task already exists").promise();
        }
        var future = executor.schedule(() -> {
                                           executeTaskAndWait(name, task);
                                           tasks.remove(name);
                                       },
                                       delay.millis(),
                                       TimeUnit.MILLISECONDS);
        var entry = new TaskEntry(name, future);
        tasks.put(name, entry);
        log.debug("Scheduled one-shot task '{}' with delay: {}ms", name, delay.millis());
        return Promise.success(entry);
    }

    @Override
    public Promise<Boolean> cancel(String name) {
        var entry = tasks.remove(name);
        if (entry != null) {
            entry.cancel();
            log.debug("Cancelled task '{}'", name);
            return Promise.success(true);
        }
        return Promise.success(false);
    }

    @Override
    public Promise<Option<ScheduledTaskHandle>> getTask(String name) {
        return Promise.success(Option.option(tasks.get(name)));
    }

    @Override
    public Promise<List<ScheduledTaskHandle>> listTasks() {
        return Promise.success(List.copyOf(tasks.values()));
    }

    @Override
    public Promise<Boolean> isScheduled(String name) {
        var entry = tasks.get(name);
        return Promise.success(entry != null && entry.isActive());
    }

    private void executeTask(String name, Fn0<Promise<Unit>> task) {
        try{
            task.apply()
                .onFailure(cause -> log.warn("Task '{}' failed: {}",
                                             name,
                                             cause.message()));
        } catch (Exception e) {
            log.warn("Task '{}' threw exception: {}", name, e.getMessage());
        }
    }

    private void executeTaskAndWait(String name, Fn0<Promise<Unit>> task) {
        try{
            task.apply()
                .await(TimeSpan.timeSpan(5)
                               .minutes())
                .onFailure(cause -> log.warn("Task '{}' failed: {}",
                                             name,
                                             cause.message()));
        } catch (Exception e) {
            log.warn("Task '{}' threw exception: {}", name, e.getMessage());
        }
    }

    private record TaskEntry(String name, ScheduledFuture< ?> future, AtomicBoolean cancelled) implements ScheduledTaskHandle {
        TaskEntry(String name, ScheduledFuture< ?> future) {
            this(name, future, new AtomicBoolean(false));
        }

        @Override
        public boolean cancel() {
            cancelled.set(true);
            return future.cancel(false);
        }

        @Override
        public boolean isActive() {
            return ! cancelled.get() && !future.isCancelled() && !future.isDone();
        }
    }
}
