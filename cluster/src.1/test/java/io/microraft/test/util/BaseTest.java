package io.microraft.test.util;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

@ExtendWith(BaseTest.TestLifecycleListener.class)
public abstract class BaseTest {

    static final Logger LOGGER = LoggerFactory.getLogger("Test");

    public static class TestLifecycleListener implements TestWatcher, BeforeTestExecutionCallback {

        private long start;

        @Override
        public void beforeTestExecution(ExtensionContext context) {
            start = System.nanoTime();
            String methodName = context.getTestMethod().map(method -> method.getName()).orElse("Unknown");
            LOGGER.info("- STARTED: " + methodName);
        }

        @Override
        public void testSuccessful(ExtensionContext context) {
            logDuration(context, "+ SUCCEEDED");
        }

        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            logDuration(context, "- FAILED");
        }

        private void logDuration(ExtensionContext context, String status) {
            long durationNanos = System.nanoTime() - start;
            long durationMicros = durationNanos / 1_000;
            long durationMillis = durationMicros / 1_000;
            long durationSeconds = durationMillis / 1_000;

            long duration = durationSeconds > 0
                    ? durationSeconds
                    : (durationMillis > 0 ? durationMillis : (durationMicros > 0 ? durationMicros : durationNanos));
            String unit = durationSeconds > 0
                    ? "secs"
                    : (durationMillis > 0 ? "millis" : (durationMicros > 0 ? "micros" : "nanos"));

            String methodName = context.getTestMethod().map(method -> method.getName()).orElse("Unknown");
            LOGGER.info(String.format("%s: %s IN %d %s", status, methodName, duration, unit));
        }
    }
}