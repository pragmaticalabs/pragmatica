package org.pragmatica.lang.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.CircuitBreaker.CircuitBreakerError.CircuitBreakerOpenError;
import org.pragmatica.lang.utils.CircuitBreaker.State;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class CircuitBreakerTest {
    private static final Cause TEST_ERROR = () -> "Test error";
    private static final Cause IGNORED_ERROR = () -> "Ignored error";

    private CircuitBreaker circuitBreaker;
    private TestTimeSource timeSource;

    private static class TestTimeSource implements TimeSource {
        private TimeSpan currentTime = timeSpan(0).nanos();

        @Override
        public long nanoTime() {
            return currentTime.nanos();
        }

        public void advanceTime(long millis) {
            currentTime = currentTime.plus(millis, TimeUnit.MILLISECONDS);
        }
    }

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();

        circuitBreaker = CircuitBreaker.builder()
                                       .failureThreshold(3)
                                       .resetTimeout(timeSpan(100).millis())
                                       .testAttempts(2)
                                       .shouldTrip(cause -> cause == TEST_ERROR)
                                       .timeSource(timeSource);
    }

    @Test
    void execute_successfulOperation_remainsClosedWithZeroFailures() {
        circuitBreaker.execute(() -> Promise.success("Success"))
                      .await()
                      .onFailureRun(Assertions::fail)
                      .onSuccess(value -> assertThat(value).isEqualTo("Success"));

        assertThat(circuitBreaker.state()).isEqualTo(State.CLOSED);
        assertThat(circuitBreaker.failureCount()).isEqualTo(0);
    }

    @Test
    void execute_twoFailures_recordsFailureCount() {
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();

        assertThat(circuitBreaker.state()).isEqualTo(State.CLOSED);
        assertThat(circuitBreaker.failureCount()).isEqualTo(2);
    }

    @Test
    void execute_nonTrippingFailures_doesNotCountFailures() {
        circuitBreaker.execute(IGNORED_ERROR::promise).await();
        circuitBreaker.execute(IGNORED_ERROR::promise).await();
        circuitBreaker.execute(IGNORED_ERROR::promise).await();
        circuitBreaker.execute(IGNORED_ERROR::promise).await();

        assertThat(circuitBreaker.state()).isEqualTo(State.CLOSED);
        assertThat(circuitBreaker.failureCount()).isEqualTo(0);
    }

    @Test
    void execute_thresholdExceeded_opensCircuit() {
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();

        assertThat(circuitBreaker.state()).isEqualTo(State.OPEN);
    }

    @Test
    void execute_circuitOpen_rejectsOperations() {
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();

        var callCount = new AtomicInteger(0);
        circuitBreaker.execute(() -> {
                          callCount.incrementAndGet();
                          return Promise.success("Should not be executed");
                      })
                      .await()
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause).isInstanceOf(CircuitBreakerOpenError.class));
        assertThat(callCount.get())
            .as("Operation should not be executed when circuit is open")
            .isEqualTo(0);
    }

    @Test
    void execute_afterResetTimeout_transitionsToHalfOpen() {
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();
        assertThat(circuitBreaker.state()).isEqualTo(State.OPEN);

        timeSource.advanceTime(150); // longer than reset timeout

        circuitBreaker.execute(() -> Promise.success("Test operation"))
                      .await()
                      .onFailureRun(Assertions::fail);

        assertThat(circuitBreaker.state()).isEqualTo(State.HALF_OPEN);
    }

    @Test
    void execute_successfulTestsInHalfOpen_transitionsToClosed() {
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();

        assertThat(circuitBreaker.state()).isEqualTo(State.OPEN);

        // Move to HALF_OPEN state by advancing time
        timeSource.advanceTime(150);

        circuitBreaker.execute(() -> Promise.success("Test 1")).await();
        var result = circuitBreaker.execute(() -> Promise.success("Test 2")).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(circuitBreaker.state()).isEqualTo(State.CLOSED);
        assertThat(circuitBreaker.failureCount()).isEqualTo(0);
    }

    @Test
    void execute_failureDuringHalfOpen_transitionsBackToOpen() {
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();
        assertThat(circuitBreaker.state()).isEqualTo(State.OPEN);

        // Move to HALF_OPEN state by advancing time
        timeSource.advanceTime(150);

        // Verify we're in HALF_OPEN state
        circuitBreaker.execute(() -> Promise.success("First operation")).await();
        assertThat(circuitBreaker.state()).isEqualTo(State.HALF_OPEN);

        circuitBreaker.execute(TEST_ERROR::<String>promise)
                      .await()
                      .onSuccessRun(Assertions::fail);

        assertThat(circuitBreaker.state()).isEqualTo(State.OPEN);
    }

    @Test
    void timeSinceLastStateChange_afterStateChange_returnsCorrectDuration() {
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();

        timeSource.advanceTime(50);

        assertThat(circuitBreaker.timeSinceLastStateChange().millis()).isEqualTo(50);
    }

    @Test
    void execute_circuitOpenWithElapsedTime_returnsCorrectRetryTime() {
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();

        timeSource.advanceTime(40);

        circuitBreaker.execute(() -> Promise.success("Should not execute"))
                      .await()
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> {
                          assertThat(cause).isInstanceOf(CircuitBreakerOpenError.class);
                          var error = (CircuitBreakerOpenError) cause;
                          assertThat(error.retryTime().millis()).isEqualTo(60);
                      });
    }

    @Test
    void execute_inHalfOpen_requiresMultipleSuccessfulTestsToClose() {
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();
        circuitBreaker.execute(TEST_ERROR::promise).await();

        timeSource.advanceTime(150);

        circuitBreaker.execute(() -> Promise.success("First test")).await();

        assertThat(circuitBreaker.state()).isEqualTo(State.HALF_OPEN);

        circuitBreaker.execute(() -> Promise.success("Second test"))
                      .await()
                      .onFailureRun(Assertions::fail);

        assertThat(circuitBreaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void execute_concurrentOperations_handlesCorrectly() {
        final int threads = 10;

        // Create a special circuit breaker with real time source for this test
        var breaker = CircuitBreaker.builder()
                                    .failureThreshold(3)
                                    .resetTimeout(timeSpan(100).millis())
                                    .testAttempts(2)
                                    .shouldTrip(cause -> cause == TEST_ERROR)
                                    .withDefaultTimeSource();

        Fn2<Promise<String>, Integer, Promise<String>> halfFail = (index, promise) ->
                (index < 5)
                        ? promise.fail(TEST_ERROR)
                        : promise.succeed("Success from thread " + index);

        var promises = IntStream.range(0, threads)
                                .mapToObj(index -> breaker.execute(() -> Promise.<String>promise(
                                        promise -> halfFail.apply(index, promise))))
                                .toList();

        Promise.allOf(promises)
               .await(timeSpan(2).seconds())
               .onFailureRun(Assertions::fail)
               .onSuccess(values -> assertThat(values).hasSize(threads));
    }

    @Test
    void execute_multipleOperationsWhileClosed_allSucceed() {
        for (int i = 0; i < 10; i++) {
            final var index = i;

            circuitBreaker.execute(() -> Promise.success(index))
                          .await()
                          .onFailureRun(Assertions::fail)
                          .onSuccess(value -> assertThat(value).isEqualTo(index));
        }

        assertThat(circuitBreaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void execute_mixedTrippingAndNonTripping_countsOnlyConfiguredFailures() {
        circuitBreaker.execute(TEST_ERROR::promise).await();   // Counts toward threshold
        circuitBreaker.execute(IGNORED_ERROR::promise).await(); // Doesn't count
        circuitBreaker.execute(TEST_ERROR::promise).await();   // Counts toward threshold

        assertThat(circuitBreaker.state()).isEqualTo(State.CLOSED);
        assertThat(circuitBreaker.failureCount()).isEqualTo(2);

        circuitBreaker.execute(TEST_ERROR::promise).await();

        assertThat(circuitBreaker.state()).isEqualTo(State.OPEN);
    }
}
