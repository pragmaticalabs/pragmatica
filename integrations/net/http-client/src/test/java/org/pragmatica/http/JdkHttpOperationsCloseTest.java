package org.pragmatica.http;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.AsyncCloseable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1097: `JdkHttpOperations` wraps a `java.net.http.HttpClient` that owns a selector-manager thread
/// and had no close, so every instance leaked it. The pin is the thread itself, counted before, during
/// and after, rather than the promise `close()` returns (which would succeed for a no-op).
class JdkHttpOperationsCloseTest {
    static long selectorThreads() {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .filter(t -> t.getName().startsWith("HttpClient-") && t.getName().endsWith("-SelectorManager"))
                     .filter(Thread::isAlive)
                     .count();
    }

    static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Test
    void implementsAsyncCloseable_soReleaseDispatchCanSeeIt() {
        assertThat(JdkHttpOperations.jdkHttpOperations()).isInstanceOf(AsyncCloseable.class);
    }

    @Test
    void close_endsTheSelectorThread_forEveryFactory() throws InterruptedException {
        var baseline = selectorThreads();
        var created = new JdkHttpOperations[]{JdkHttpOperations.jdkHttpOperations(),
                                              JdkHttpOperations.jdkHttpOperations(HttpClient.newHttpClient()),
                                              JdkHttpOperations.jdkHttpOperations(Duration.ofSeconds(1),
                                                                                  HttpClient.Redirect.NORMAL,
                                                                                  Option.none())};

        assertThat(eventually(() -> selectorThreads() >= baseline + 3)).as("selector threads exist while open")
                                                                      .isTrue();
        for (var ops : created) {
            assertThat(ops.close().await(timeSpan(10).seconds()).isSuccess()).isTrue();
        }
        assertThat(eventually(() -> selectorThreads() <= baseline)).as("selector threads gone after close; baseline %d, now %d",
                                                                      baseline,
                                                                      selectorThreads())
                                                                  .isTrue();
        assertThat(created[0].client().isTerminated()).isTrue();
    }
}
