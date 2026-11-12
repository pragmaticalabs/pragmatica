package org.pragmatica.aether.node;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.invoke.InvocationContext;
import org.pragmatica.lang.ContextPropagation;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Core runs a NoOp [ContextPropagation], so no core test can observe whether `Promise.continueOn` restores the
/// context snapshot around the hop. This module installs the real implementation (`AetherContextPropagation`), which
/// makes the restore observable: dropping `runWith(snapshot, ..)` in the hop turns this test red.
class ContinueOnContextPropagationTest {
    private static final TimeSpan BOUND = TimeSpan.timeSpan(5).seconds();
    private static final int ROUNDS = 300;
    private static final String REQUEST_ID = "req-9";

    @Test
    void realPropagationIsInstalled() {
        assertEquals("org.pragmatica.aether.invoke.AetherContextPropagation",
                     ContextPropagation.INSTANCE.getClass().getName());
    }

    @Test
    void pendingSource_resolverContext_survivesTheHop() {
        var pool = Executors.newSingleThreadExecutor();
        var resolver = Executors.newSingleThreadExecutor();

        try {
            for (var round = 0; round < ROUNDS; round++) {
                assertEquals(REQUEST_ID, resolveUnderContext(pool, resolver), "round " + round);
            }
        } finally {
            pool.shutdownNow();
            resolver.shutdownNow();
        }
    }

    private static String resolveUnderContext(ExecutorService pool, ExecutorService resolver) {
        var source = Promise.<Integer> promise();
        var out = source.continueOn(pool)
                        .map(_ -> currentRequestId());

        resolver.execute(() -> resolveWithRequestId(source));

        return out.await(BOUND).unwrap();
    }

    private static void resolveWithRequestId(Promise<Integer> source) {
        InvocationContext.runWithRequestId(REQUEST_ID, () -> source.succeed(1));
    }

    private static String currentRequestId() {
        return InvocationContext.currentRequestId().or("NONE");
    }
}
