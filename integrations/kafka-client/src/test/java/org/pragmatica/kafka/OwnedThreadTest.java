package org.pragmatica.kafka;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


class OwnedThreadTest {
    private static Result<Unit> hold(CountDownLatch holding, CountDownLatch release) {
        holding.countDown();
        awaitQuietly(release);

        return Result.unitResult();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void submit_pastTheQueueBound_failsRetriableWithQueueFullOrigin() throws InterruptedException {
        var thread = OwnedThread.ownedThread("bound-test", 2);
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var running = thread.submit(() -> hold(holding, release));

        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
        var first = thread.submit(Result::unitResult);
        var second = thread.submit(Result::unitResult);
        var refused = thread.submit(Result::unitResult);
        var cause = refused.await().<Cause> fold(c -> c, _ -> fail("expected a refusal"));

        assertThat(cause).isInstanceOf(KafkaError.Retriable.class);
        assertThat(((KafkaError.Retriable) cause).origin()).isSameAs(KafkaError.General.SUBMISSION_QUEUE_FULL);
        release.countDown();
        assertThat(running.await().isSuccess()).isTrue();
        assertThat(first.await().isSuccess()).isTrue();
        assertThat(second.await().isSuccess()).isTrue();
        thread.shutdown();
    }

    @Test
    void submit_afterShutdown_failsClosedNotQueueFull() {
        var thread = OwnedThread.ownedThread("closed-test", 1);

        thread.shutdown();
        assertThat(thread.submit(Result::unitResult).await().<Cause> fold(c -> c, _ -> fail("expected a refusal"))).isSameAs(KafkaError.General.CLOSED);
    }
}
