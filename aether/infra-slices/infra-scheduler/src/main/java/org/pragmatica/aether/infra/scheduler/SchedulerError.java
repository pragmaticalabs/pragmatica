package org.pragmatica.aether.infra.scheduler;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Errors for scheduler operations.
public sealed interface SchedulerError extends Cause {
    /// Task scheduling failed.
    record SchedulingFailed(String taskName, String reason) implements SchedulerError {
        public static Result<SchedulingFailed> schedulingFailed(String taskName, String reason) {
            return success(new SchedulingFailed(taskName, reason));
        }

        @Override
        public String message() {
            return "Failed to schedule task '" + taskName + "': " + reason;
        }
    }

    /// Task execution failed.
    record ExecutionFailed(String taskName, Option<Throwable> cause) implements SchedulerError {
        public static Result<ExecutionFailed> executionFailed(String taskName, Option<Throwable> cause) {
            return success(new ExecutionFailed(taskName, cause));
        }

        @Override
        public String message() {
            var causeMessage = cause.map(ExecutionFailed::throwableMessage)
                                    .or("");
            return "Task '" + taskName + "' execution failed" + causeMessage;
        }

        private static String throwableMessage(Throwable t) {
            return ": " + t.getMessage();
        }
    }

    /// Invalid cron expression.
    record InvalidCronExpression(String expression, String reason) implements SchedulerError {
        public static Result<InvalidCronExpression> invalidCronExpression(String expression, String reason) {
            return success(new InvalidCronExpression(expression, reason));
        }

        @Override
        public String message() {
            return "Invalid cron expression '" + expression + "': " + reason;
        }
    }

    /// Task not found.
    record TaskNotFound(String taskName) implements SchedulerError {
        public static Result<TaskNotFound> taskNotFound(String taskName) {
            return success(new TaskNotFound(taskName));
        }

        @Override
        public String message() {
            return "Task not found: " + taskName;
        }
    }

    record unused() implements SchedulerError {
        @Override
        public String message() {
            return "";
        }
    }
}
