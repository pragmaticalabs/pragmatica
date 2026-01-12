package org.pragmatica.aether.infra.scheduler;
/**
 * Handle for a scheduled task that can be used to cancel it.
 */
public interface ScheduledTaskHandle {
    /**
     * Cancel this scheduled task.
     * After cancellation, the task will not execute again.
     *
     * @return true if the task was successfully cancelled
     */
    boolean cancel();

    /**
     * Check if this task is still active.
     *
     * @return true if the task has not been cancelled
     */
    boolean isActive();

    /**
     * Get the task name.
     *
     * @return task name
     */
    String name();
}
