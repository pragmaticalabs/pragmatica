package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;

// THROWAWAY (#1344 repro): as AAACrashGoodbyeDumpTest, but the message throws an Error, which
// surefire's dumpException (catch Exception) does not swallow: NO fork dump is written either.
// Only the plan witness can see this one.
class AAACrashGoodbyeSilentTest {
    static final class HostileOutOfMemoryError extends OutOfMemoryError {
        @Override
        public String getLocalizedMessage() {
            throw new InternalError("the fork's error report cannot be built");
        }
    }

    @Test
    void first_passes() {
    }

    @Test
    void second_escapesTheLauncher() {
        throw new HostileOutOfMemoryError();
    }

    @Test
    void third_neverRuns() {
    }
}
