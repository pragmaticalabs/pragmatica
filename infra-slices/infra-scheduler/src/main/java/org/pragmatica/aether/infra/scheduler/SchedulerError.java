package org.pragmatica.aether.infra.scheduler;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

/**
 * Errors for scheduler operations.
 */
public sealed interface SchedulerError extends Cause {
    /**
     * Task scheduling failed.
     */
    record SchedulingFailed(String taskName, String reason) implements SchedulerError {
        @Override
        public String message() {
            return "Failed to schedule task '" + taskName + "': " + reason;
        }
    }

    /**
     * Task execution failed.
     */
    record ExecutionFailed(String taskName, Option<Throwable> cause) implements SchedulerError {
        @Override
        public String message() {
            return "Task '" + taskName + "' execution failed" + cause.map(t -> ": " + t.getMessage())
                                                                    .or("");
        }
    }

    /**
     * Invalid cron expression.
     */
    record InvalidCronExpression(String expression, String reason) implements SchedulerError {
        @Override
        public String message() {
            return "Invalid cron expression '" + expression + "': " + reason;
        }
    }

    /**
     * Task not found.
     */
    record TaskNotFound(String taskName) implements SchedulerError {
        @Override
        public String message() {
            return "Task not found: " + taskName;
        }
    }
}
