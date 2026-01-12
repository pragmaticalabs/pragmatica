package org.pragmatica.aether.infra.scheduler;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;

/**
 * Scheduler service for executing tasks at specified intervals.
 * Supports fixed-rate scheduling, fixed-delay scheduling, and one-shot execution.
 */
public interface Scheduler extends Slice {
    /**
     * Schedule a task to run at a fixed rate.
     * If the task takes longer than the period, executions may overlap.
     *
     * @param name         Task name for identification
     * @param initialDelay Delay before first execution
     * @param period       Time between start of successive executions
     * @param task         Task to execute
     * @return Handle to cancel the scheduled task
     */
    Promise<ScheduledTaskHandle> scheduleAtFixedRate(String name,
                                                     TimeSpan initialDelay,
                                                     TimeSpan period,
                                                     Fn0<Promise<Unit>> task);

    /**
     * Schedule a task to run with a fixed delay between executions.
     * Next execution starts after the previous one completes plus the delay.
     *
     * @param name         Task name for identification
     * @param initialDelay Delay before first execution
     * @param delay        Time between end of one execution and start of next
     * @param task         Task to execute
     * @return Handle to cancel the scheduled task
     */
    Promise<ScheduledTaskHandle> scheduleWithFixedDelay(String name,
                                                        TimeSpan initialDelay,
                                                        TimeSpan delay,
                                                        Fn0<Promise<Unit>> task);

    /**
     * Schedule a one-shot task to run after a delay.
     *
     * @param name  Task name for identification
     * @param delay Delay before execution
     * @param task  Task to execute
     * @return Handle to cancel the scheduled task
     */
    Promise<ScheduledTaskHandle> schedule(String name, TimeSpan delay, Fn0<Promise<Unit>> task);

    /**
     * Cancel a scheduled task by name.
     *
     * @param name Task name
     * @return true if the task was found and cancelled
     */
    Promise<Boolean> cancel(String name);

    /**
     * Get a task handle by name.
     *
     * @param name Task name
     * @return Option containing the handle if found
     */
    Promise<Option<ScheduledTaskHandle>> getTask(String name);

    /**
     * List all active scheduled tasks.
     *
     * @return List of active task handles
     */
    Promise<List<ScheduledTaskHandle>> listTasks();

    /**
     * Check if a task is currently scheduled.
     *
     * @param name Task name
     * @return true if the task exists and is active
     */
    Promise<Boolean> isScheduled(String name);

    /**
     * Factory method for default implementation.
     *
     * @return Scheduler instance
     */
    static Scheduler scheduler() {
        return new DefaultScheduler();
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
