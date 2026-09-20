package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;

// THROWAWAY (#1344 repro): the P18 shape, made deterministic. JUnit rethrows OutOfMemoryError as
// unrecoverable, so it escapes the launcher into ForkedBooter.execute()'s catch block; there
// `logger.error(e.getLocalizedMessage(), e)` throws (as writing the trace did in P18 with StringBuffer
// poisoned), the catch block is abandoned, and the finally block says goodbye and exits 0.
// A RuntimeException from the message lets surefire's dumpException swallow it, so a fork dump IS written.
class AAACrashGoodbyeDumpTest {
    static final class HostileOutOfMemoryError extends OutOfMemoryError {
        @Override
        public String getLocalizedMessage() {
            throw new IllegalStateException("the fork's error report cannot be built");
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
