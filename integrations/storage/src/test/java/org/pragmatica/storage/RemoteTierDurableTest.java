package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cloud.aws.s3.S3Client;
import org.pragmatica.cloud.aws.s3.S3Error;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.Unit.unit;

/// #249: the production [RemoteTier] ([RemoteTier#durableRemoteTier]) without a network: durability only on
/// the production path, shared always, bounded in-flight requests, per-request timeouts, classified failures
/// and the boot probe. The same properties against MinIO live in [RemoteTierS3IntegrationTest].
class RemoteTierDurableTest {
    private static final byte[] CONTENT = "durable-remote-content".getBytes(StandardCharsets.UTF_8);

    private ControllableS3 s3;

    @BeforeEach
    void setUp() {
        s3 = new ControllableS3();
    }

    private RemoteTier durable(int maxInflight) {
        return RemoteTier.durableRemoteTier(s3, "cluster-a/", maxInflight, timeSpan(30).seconds())
                         .fold(cause -> fail(cause.message()), tier -> tier);
    }

    private static BlockId idOf(byte[] content) {
        return BlockId.blockId(content).fold(cause -> fail(cause.message()), id -> id);
    }

    @Nested
    class Flags {

        /// Mutation that reddens it: hard-code `isDurable` true (then the test factory reports durable).
        @Test
        void isDurable_onlyForTheProductionConstructionPath() {
            assertThat(durable(4).isDurable()).isTrue();
            assertThat(RemoteTier.remoteTier(s3, "blocks", 1024).isDurable()).isFalse();
        }

        @Test
        void isShared_always() {
            assertThat(durable(4).isShared()).isTrue();
            assertThat(RemoteTier.remoteTier(s3, "blocks", 1024).isShared()).isTrue();
        }

        @Test
        void durableRemoteTier_nonPositiveMaxInflight_isRefused() {
            RemoteTier.durableRemoteTier(s3, "p", 0, timeSpan(1).seconds())
                      .onSuccess(_ -> fail("Expected refusal"));
        }
    }

    @Nested
    class Keys {

        @Test
        void keys_liveUnderThePrefix_withOneSeparator() {
            var id = idOf(CONTENT);

            durable(4).put(id, CONTENT).await().onFailure(cause -> fail(cause.message()));
            RemoteTier.remoteTier(s3, "legacy", 1024).put(id, CONTENT).await();

            assertThat(s3.objects.keySet()).containsExactlyInAnyOrder("cluster-a/" + id.shardPrefix() + "/" + id.hexString(),
                                                                      "legacy/" + id.shardPrefix() + "/" + id.hexString());
        }
    }

    @Nested
    class Bounds {

        /// Mutation that reddens it: run requests outside the limiter (`request` calling `call` directly).
        @Test
        void inflight_neverExceedsMaxInflight() {
            s3.holdPuts = true;
            var tier = durable(2);
            var puts = new ArrayList<Promise<Unit>>();

            for (var i = 0; i < 5; i++) {
                var content = ("block-" + i).getBytes(StandardCharsets.UTF_8);
                puts.add(tier.put(idOf(content), content));
            }

            settle(() -> s3.started.get() == 2);
            assertThat(s3.started.get()).isEqualTo(2);
            s3.releaseOne();
            settle(() -> s3.started.get() == 3);
            assertThat(s3.started.get()).isEqualTo(3);
            s3.releaseAll();
            settle(() -> s3.started.get() == 5);
            s3.releaseAll();
            puts.forEach(put -> put.await().onFailure(cause -> fail(cause.message())));
            assertThat(s3.maxConcurrent.get()).isEqualTo(2);
            assertThat(s3.started.get()).isEqualTo(5);
        }

        /// Mutation that reddens it: drop `bounded` (no per-request timeout): the put never resolves.
        @Test
        void request_hangingProvider_timesOutAsUnavailable() {
            s3.holdPuts = true;
            var tier = RemoteTier.durableRemoteTier(s3, "p", 1, timeSpan(100).millis())
                                 .fold(cause -> fail(cause.message()), t -> t);

            tier.put(idOf(CONTENT), CONTENT)
                .await(timeSpan(5).seconds())
                .onSuccess(_ -> fail("Expected a timeout"))
                .onFailure(cause -> assertUnavailable(cause));
        }
    }

    @Nested
    class Classification {

        /// An unavailable provider is retriable and never "absent".
        @Test
        void get_providerUnavailable_failsRetriable_notNone() {
            s3.failure = new S3Error.ApiError(503, "SlowDown", "reduce rate");

            durable(4).get(idOf(CONTENT))
                      .await()
                      .onSuccess(opt -> fail("Expected a failure, got " + opt))
                      .onFailure(RemoteTierDurableTest::assertUnavailable);
        }

        @Test
        void put_accessDenied_isRejectedAndTerminal() {
            s3.failure = new S3Error.AccessDenied("bad key");

            durable(4).put(idOf(CONTENT), CONTENT)
                      .await()
                      .onSuccess(_ -> fail("Expected a failure"))
                      .onFailure(cause -> {
                          assertThat(cause).isInstanceOf(RemoteTierError.Rejected.class);
                          assertThat(cause.isTerminal()).isTrue();
                          assertThat(cause.source()).isEqualTo(Option.some(s3.failure));
                      });
        }

        @Test
        void classify_throttlingAndTimeoutStatuses_areUnavailable() {
            assertUnavailable(RemoteTier.classify("PUT", "k", new S3Error.ApiError(429, "SlowDown", "")));
            assertUnavailable(RemoteTier.classify("PUT", "k", new S3Error.ApiError(408, "RequestTimeout", "")));
            assertUnavailable(RemoteTier.classify("PUT", "k", new S3Error.NetworkError("refused", Option.none())));
            assertThat(RemoteTier.classify("PUT", "k", new S3Error.ApiError(400, "InvalidRequest", "")))
                .isInstanceOf(RemoteTierError.Rejected.class);
            assertThat(RemoteTier.classify("PUT", "k", new S3Error.ApiError(301, "PermanentRedirect", "wrong region")))
                .isInstanceOf(RemoteTierError.Rejected.class);
        }
    }

    @Nested
    class Probe {

        @Test
        void probe_putGetDelete_leavesNothingBehind() {
            durable(4).probe("node-1")
                      .await()
                      .onFailure(cause -> fail(cause.message()));

            assertThat(s3.calls).containsExactly("PUT cluster-a/.aether-probe/node-1",
                                                 "GET cluster-a/.aether-probe/node-1",
                                                 "DELETE cluster-a/.aether-probe/node-1");
            assertThat(s3.objects).isEmpty();
        }

        @Test
        void probe_readBackDiffers_failsWithMismatch() {
            s3.corruptReads = true;

            durable(4).probe("node-1")
                      .await()
                      .onSuccess(_ -> fail("Expected a mismatch"))
                      .onFailure(cause -> assertThat(cause).isInstanceOf(RemoteTierError.ProbeMismatch.class));
        }

        @Test
        void probe_unreachable_fails() {
            s3.failure = new S3Error.NetworkError("connection refused", Option.none());

            durable(4).probe("node-1")
                      .await()
                      .onSuccess(_ -> fail("Expected a failure"))
                      .onFailure(RemoteTierDurableTest::assertUnavailable);
        }
    }

    /// Promise continuations run on the scheduler, so a permit handed over by a release starts its waiter shortly
    /// after, not inline. Polls up to 5 s, then lets the caller's assertion report the state.
    private static void settle(BooleanSupplier condition) {
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        // let any over-limit start that would violate the bound surface before asserting
        var settleUntil = System.nanoTime() + 50_000_000L;
        while (System.nanoTime() < settleUntil) {
            Thread.onSpinWait();
        }
    }

    private static void assertUnavailable(Cause cause) {
        assertThat(cause).isInstanceOf(RemoteTierError.Unavailable.class);
        assertThat(cause.isTerminal()).isFalse();
    }

    /// An in-memory S3 that can fail every call, hold PUTs until released, or corrupt reads.
    static final class ControllableS3 implements S3Client {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Promise<Unit>> held = new CopyOnWriteArrayList<>();
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        volatile Cause failure;
        volatile boolean holdPuts;
        volatile boolean corruptReads;

        @Override
        public Promise<Unit> putObject(String key, byte[] content, String contentType) {
            calls.add("PUT " + key);
            if (failure != null) {
                return failure.promise();
            }
            started.incrementAndGet();
            maxConcurrent.accumulateAndGet(running.incrementAndGet(), Math::max);
            if (!holdPuts) {
                running.decrementAndGet();
                objects.put(key, content);
                return Promise.success(unit());
            }
            var gate = Promise.<Unit>promise();
            held.add(gate);
            return gate.onResultRun(running::decrementAndGet).onSuccess(_ -> objects.put(key, content));
        }

        void releaseOne() {
            var gate = held.removeFirst();
            gate.succeed(unit());
        }

        void releaseAll() {
            var gates = List.copyOf(held);
            held.removeAll(gates);
            gates.forEach(gate -> gate.succeed(unit()));
        }

        @Override
        public Promise<Option<byte[]>> getObject(String key) {
            calls.add("GET " + key);
            if (failure != null) {
                return failure.promise();
            }
            return Promise.success(Option.option(objects.get(key))
                                         .map(bytes -> corruptReads ? "corrupt".getBytes(StandardCharsets.UTF_8) : bytes));
        }

        @Override
        public Promise<Boolean> headObject(String key) {
            calls.add("HEAD " + key);
            return failure != null ? failure.promise() : Promise.success(objects.containsKey(key));
        }

        @Override
        public Promise<Unit> deleteObject(String key) {
            calls.add("DELETE " + key);
            if (failure != null) {
                return failure.promise();
            }
            objects.remove(key);
            return Promise.success(unit());
        }

        @Override
        public Promise<List<String>> listObjects(String prefix, int maxKeys) {
            return Promise.success(objects.keySet().stream().filter(key -> key.startsWith(prefix)).toList());
        }
    }
}
