package org.pragmatica.aether.infra.scheduler;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
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

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;

/// Default implementation of Scheduler using ScheduledExecutorService.
final class DefaultScheduler implements Scheduler {
    private static final Logger log = LoggerFactory.getLogger(DefaultScheduler.class);

    private final ScheduledExecutorService executor;
    private final Map<String, TaskEntry> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    DefaultScheduler() {
        this.executor = Executors.newScheduledThreadPool(Runtime.getRuntime()
                                                                .availableProcessors(),
                                                         DefaultScheduler::createDaemonThread);
    }

    private static Thread createDaemonThread(Runnable r) {
        var thread = new Thread(r, "scheduler-worker");
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public Promise<Unit> start() {
        running.set(true);
        log.info("Scheduler started");
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> stop() {
        running.set(false);
        tasks.values()
             .forEach(TaskEntry::cancel);
        tasks.clear();
        shutdownExecutor();
        log.info("Scheduler stopped");
        return Promise.success(unit());
    }

    private void shutdownExecutor() {
        executor.shutdown();
        awaitOrForceShutdown();
    }

    private void awaitOrForceShutdown() {
        try{
            var terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
            forceShutdownIfNeeded(terminated);
        } catch (InterruptedException e) {
            restoreInterruptAndShutdown();
        }
    }

    private void forceShutdownIfNeeded(boolean terminated) {
        if (!terminated) {
            executor.shutdownNow();
        }
    }

    private void restoreInterruptAndShutdown() {
        executor.shutdownNow();
        Thread.currentThread()
              .interrupt();
    }

    @Override
    public Promise<ScheduledTaskHandle> scheduleAtFixedRate(String name,
                                                            TimeSpan initialDelay,
                                                            TimeSpan period,
                                                            Fn0<Promise<Unit>> task) {
        if (tasks.containsKey(name)) {
            return SchedulerError.SchedulingFailed.schedulingFailed(name, "Task already exists")
                                 .unwrap()
                                 .promise();
        }
        var future = executor.scheduleAtFixedRate(() -> runTask(name, task),
                                                  initialDelay.millis(),
                                                  period.millis(),
                                                  TimeUnit.MILLISECONDS);
        var entry = TaskEntry.taskEntry(name, future)
                             .unwrap();
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
            return SchedulerError.SchedulingFailed.schedulingFailed(name, "Task already exists")
                                 .unwrap()
                                 .promise();
        }
        var future = executor.scheduleWithFixedDelay(() -> runTaskAndWait(name, task),
                                                     initialDelay.millis(),
                                                     delay.millis(),
                                                     TimeUnit.MILLISECONDS);
        var entry = TaskEntry.taskEntry(name, future)
                             .unwrap();
        tasks.put(name, entry);
        log.debug("Scheduled task '{}' with fixed delay: {}ms", name, delay.millis());
        return Promise.success(entry);
    }

    @Override
    public Promise<ScheduledTaskHandle> schedule(String name, TimeSpan delay, Fn0<Promise<Unit>> task) {
        if (tasks.containsKey(name)) {
            return SchedulerError.SchedulingFailed.schedulingFailed(name, "Task already exists")
                                 .unwrap()
                                 .promise();
        }
        var future = executor.schedule(() -> runOneShotTask(name, task), delay.millis(), TimeUnit.MILLISECONDS);
        var entry = TaskEntry.taskEntry(name, future)
                             .unwrap();
        tasks.put(name, entry);
        log.debug("Scheduled one-shot task '{}' with delay: {}ms", name, delay.millis());
        return Promise.success(entry);
    }

    private void runOneShotTask(String name, Fn0<Promise<Unit>> task) {
        runTaskAndWait(name, task);
        tasks.remove(name);
    }

    @Override
    public Promise<Boolean> cancel(String name) {
        var removed = option(tasks.remove(name));
        removed.onPresent(TaskEntry::cancel);
        removed.onPresent(_ -> log.debug("Cancelled task '{}'", name));
        return Promise.success(removed.isPresent());
    }

    @Override
    public Promise<Option<ScheduledTaskHandle>> getTask(String name) {
        return Promise.success(option(tasks.get(name)));
    }

    @Override
    public Promise<List<ScheduledTaskHandle>> listTasks() {
        return Promise.success(List.copyOf(tasks.values()));
    }

    @Override
    public Promise<Boolean> isScheduled(String name) {
        return Promise.success(option(tasks.get(name)).filter(TaskEntry::isActive)
                                     .isPresent());
    }

    private void runTask(String name, Fn0<Promise<Unit>> task) {
        try{
            var result = task.apply();
            result.onFailure(cause -> logTaskFailure(name, cause));
        } catch (Exception e) {
            log.warn("Task '{}' threw exception: {}", name, e.getMessage());
        }
    }

    private static final TimeSpan TASK_TIMEOUT = TimeSpan.timeSpan(5)
                                                        .minutes();

    private void runTaskAndWait(String name, Fn0<Promise<Unit>> task) {
        try{
            var awaited = task.apply()
                              .await(TASK_TIMEOUT);
            awaited.onFailure(cause -> logTaskFailure(name, cause));
        } catch (Exception e) {
            log.warn("Task '{}' threw exception: {}", name, e.getMessage());
        }
    }

    private static void logTaskFailure(String name, Cause cause) {
        log.warn("Task '{}' failed: {}", name, cause.message());
    }

    private record TaskEntry(String name, ScheduledFuture<?> future, AtomicBoolean cancelled) implements ScheduledTaskHandle {
        TaskEntry(String name, ScheduledFuture<?> future) {
            this(name, future, new AtomicBoolean(false));
        }

        static Result<TaskEntry> taskEntry(String name, ScheduledFuture<?> future) {
            return success(new TaskEntry(name, future));
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
