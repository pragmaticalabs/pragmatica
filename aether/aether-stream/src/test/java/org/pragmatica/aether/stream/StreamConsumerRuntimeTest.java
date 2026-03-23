package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamConsumerRuntime.streamConsumerRuntime;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;

class StreamConsumerRuntimeTest {

    private StreamPartitionManager manager;
    private StreamConsumerRuntime runtime;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        runtime = streamConsumerRuntime(manager);
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
        manager.close();
    }

    private void createTestStream(String name) {
        var retention = RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000);
        manager.createStream(StreamConfig.streamConfig(name, 4, retention, "earliest"));
    }

    @Nested
    class Subscribe {

        @Test
        void subscribe_success_validStream() {
            createTestStream("orders");
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> success(unit()))
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));
        }

        @Test
        void subscribe_failure_duplicateSubscription() {
            createTestStream("orders");
            var config = ConsumerConfig.consumerConfig("group-1");
            StreamConsumerRuntime.ConsumerCallback noop = (offset, payload, ts) -> success(unit());

            runtime.subscribe("orders", 0, config, noop);

            runtime.subscribe("orders", 0, config, noop)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.CONSUMER_ALREADY_SUBSCRIBED));
        }

        @Test
        void subscribe_callbackInvoked_afterPublish() throws InterruptedException {
            createTestStream("orders");
            var received = new CopyOnWriteArrayList<Long>();
            var latch = new CountDownLatch(2);

            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> {
                received.add(offset);
                latch.countDown();
                return success(unit());
            });

            manager.publishLocal("orders", 0, "event-1".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "event-2".getBytes(), 2000L);

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received).containsExactly(0L, 1L);
        }

        @Test
        void subscribe_cursorAdvances_afterSuccessfulDelivery() throws InterruptedException {
            createTestStream("orders");
            var latch = new CountDownLatch(1);

            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> {
                latch.countDown();
                return success(unit());
            });

            manager.publishLocal("orders", 0, "event-1".getBytes(), 1000L);

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            // Allow a poll cycle for cursor update
            Thread.sleep(150);

            var cursor = runtime.cursorPosition("orders", 0, "group-1");
            assertThat(cursor.isPresent()).isTrue();
            cursor.onPresent(pos -> assertThat(pos).isGreaterThanOrEqualTo(1L));
        }
    }

    @Nested
    class Unsubscribe {

        @Test
        void unsubscribe_success_existingConsumer() {
            createTestStream("orders");
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> success(unit()));

            runtime.unsubscribe("orders", 0, "group-1")
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));
        }

        @Test
        void unsubscribe_failure_nonExistent() {
            runtime.unsubscribe("orders", 0, "group-1")
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.CONSUMER_NOT_FOUND));
        }

        @Test
        void unsubscribe_stopsDelivery_afterUnsubscribe() throws InterruptedException {
            createTestStream("orders");
            var callCount = new AtomicInteger(0);
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> {
                callCount.incrementAndGet();
                return success(unit());
            });

            // Publish, wait for delivery, then unsubscribe
            manager.publishLocal("orders", 0, "event-1".getBytes(), 1000L);
            Thread.sleep(300);

            runtime.unsubscribe("orders", 0, "group-1");
            var countAfterUnsub = callCount.get();

            // Publish more events — should not be delivered
            manager.publishLocal("orders", 0, "event-2".getBytes(), 2000L);
            Thread.sleep(300);

            assertThat(callCount.get()).isEqualTo(countAfterUnsub);
        }
    }

    @Nested
    class CursorPosition {

        @Test
        void cursorPosition_none_noSubscription() {
            var cursor = runtime.cursorPosition("orders", 0, "group-1");
            assertThat(cursor.isEmpty()).isTrue();
        }

        @Test
        void cursorPosition_present_afterSubscribe() {
            createTestStream("orders");
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> success(unit()));

            var cursor = runtime.cursorPosition("orders", 0, "group-1");
            assertThat(cursor.isPresent()).isTrue();
            cursor.onPresent(pos -> assertThat(pos).isEqualTo(0L));
        }
    }

    @Nested
    class RetryStrategy {

        @Test
        void retry_redeliversOnFailure_thenSucceeds() throws InterruptedException {
            createTestStream("orders");
            var attempts = new AtomicInteger(0);
            var latch = new CountDownLatch(1);

            var config = ConsumerConfig.consumerConfig("group-1", 1,
                ProcessingMode.ORDERED, ErrorStrategy.RETRY);

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> {
                if (attempts.incrementAndGet() < 3) {
                    return StreamError.General.BUFFER_EMPTY.result();
                }
                latch.countDown();
                return success(unit());
            });

            manager.publishLocal("orders", 0, "event".getBytes(), 1000L);

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(attempts.get()).isGreaterThanOrEqualTo(3);
        }

        @Test
        void retry_sendsToDeadLetter_afterMaxRetries() throws InterruptedException {
            createTestStream("orders");
            var latch = new CountDownLatch(1);

            var config = ConsumerConfig.consumerConfig("group-1", 1,
                ProcessingMode.ORDERED, ErrorStrategy.RETRY);

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> {
                var result = StreamError.General.BUFFER_EMPTY.<Unit>result();
                // Signal when we've been called enough for max retries
                latch.countDown();
                return result;
            });

            manager.publishLocal("orders", 0, "event".getBytes(), 1000L);

            // Wait for retries to exhaust (5 retries with backoff)
            Thread.sleep(5000);

            var dlEntries = runtime.deadLetterHandler().read("orders", 10);
            assertThat(dlEntries).isNotEmpty();
            assertThat(dlEntries.getFirst().offset()).isEqualTo(0L);
        }
    }

    @Nested
    class SkipStrategy {

        @Test
        void skip_advancesCursor_onFailure() throws InterruptedException {
            createTestStream("orders");
            var deliveredOffsets = new CopyOnWriteArrayList<Long>();
            var latch = new CountDownLatch(2);

            var config = ConsumerConfig.consumerConfig("group-1", 1,
                ProcessingMode.ORDERED, ErrorStrategy.SKIP);

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> {
                deliveredOffsets.add(offset);
                latch.countDown();
                if (offset == 0L) {
                    return StreamError.General.BUFFER_EMPTY.result();
                }
                return success(unit());
            });

            manager.publishLocal("orders", 0, "fail-event".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "ok-event".getBytes(), 2000L);

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            // First event was skipped, second delivered
            assertThat(deliveredOffsets).contains(0L, 1L);

            // Dead letter should have the skipped event
            var dlEntries = runtime.deadLetterHandler().read("orders", 10);
            assertThat(dlEntries).isNotEmpty();
            assertThat(dlEntries.getFirst().offset()).isEqualTo(0L);
        }
    }

    @Nested
    class StallStrategy {

        @Test
        void stall_stopsDelivery_onFailure() throws InterruptedException {
            createTestStream("orders");
            var deliveredOffsets = new CopyOnWriteArrayList<Long>();

            var config = ConsumerConfig.consumerConfig("group-1", 1,
                ProcessingMode.ORDERED, ErrorStrategy.STALL);

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> {
                deliveredOffsets.add(offset);
                if (offset == 0L) {
                    return StreamError.General.BUFFER_EMPTY.result();
                }
                return success(unit());
            });

            manager.publishLocal("orders", 0, "fail-event".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "should-not-deliver".getBytes(), 2000L);

            Thread.sleep(500);

            // Only the first event should be delivered; consumer is stalled
            assertThat(deliveredOffsets).containsExactly(0L);

            // Cursor should not have advanced
            var cursor = runtime.cursorPosition("orders", 0, "group-1");
            cursor.onPresent(pos -> assertThat(pos).isEqualTo(0L));
        }
    }

    @Nested
    class CloseTests {

        @Test
        void close_stopsAllConsumers() throws Exception {
            createTestStream("orders");
            var config = ConsumerConfig.consumerConfig("group-1");

            runtime.subscribe("orders", 0, config, (offset, payload, ts) -> success(unit()));
            runtime.close();

            // After close, subscribe should fail
            runtime.subscribe("orders", 0, ConsumerConfig.consumerConfig("group-2"),
                              (offset, payload, ts) -> success(unit()))
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.CONSUMER_RUNTIME_CLOSED));
        }
    }
}
