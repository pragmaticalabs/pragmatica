// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.EncryptingStorageTier;
import org.pragmatica.storage.EncryptionError;
import org.pragmatica.storage.StorageError;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1052: the post-formation DHT encryption-marker check treats a definite refusal as fatal and
/// everything else as transient -- retry with backoff, keep the tier gated, stay not-ready.
///
/// Every read here goes through the ASSEMBLED `StorageInstance` (memory -> disk -> DHT waterfall), never
/// through the gate promise alone, so "refused" and "served" are the outermost observable: what a real
/// caller of the storage instance gets back. The scripted client counts marker-key and block-key gets
/// separately, so "the read never reached the DHT" is measured, not inferred.
///
/// The retry knobs use the package-private five-argument `StorageFactory.verifyDhtMarker` seam (150 ms
/// attempt bound, 20 ms fixed backoff) so the retry cases run in milliseconds. The admission bound a
/// gated read waits out is NOT shrunk: it is `DhtStorageTier`'s real 30 s production default, because
/// the claim under test is that a check pending longer than that bound still refuses the read rather
/// than admitting it. One test therefore takes ~30 s by design. The production cadence
/// ([#verifyDhtMarker_retriesTransientFailure_throughProductionEntryPoint]) is exercised once, with the
/// two-argument entry point that also exists before this fix.
class StorageFactoryDhtMarkerRetryTest {
    private static final String INSTANCE = "vault";
    private static final String NODE_ID = "node-1";
    private static final byte[] BLOCK = "dht-marker-retry-block-1052".getBytes(StandardCharsets.UTF_8);
    private static final TimeSpan SHORT_ATTEMPT_TIMEOUT = timeSpan(150).millis();
    private static final BackoffStrategy FAST_BACKOFF = BackoffStrategy.fixed().interval(timeSpan(20).millis());
    private static final BooleanSupplier NEVER_STOPPED = () -> false;
    private static final TimeSpan SETTLE_BOUND = timeSpan(10).seconds();
    /// Longer than `DhtStorageTier`'s 30 s production admission bound, so a gated read settles on its own.
    private static final TimeSpan GATED_READ_BOUND = timeSpan(60).seconds();

    private static final String FACTORY_LOGGER = StorageFactory.class.getName();
    private static final String ATTEMPT_WARN_PREFIX = "DHT encryption-marker check attempt ";

    @TempDir
    Path tempDir;

    private CapturingAppender appender;
    private LoggerContext loggerContext;

    /// Same shape as `MavenProtocolRoutesAuthTest`: the node logs through SLF4J onto log4j2, so the
    /// appender attaches to the log4j2 `LoggerConfig` for `StorageFactory`'s logger name (#1077: a
    /// `System.Logger` emission would bypass this and the node's appenders alike).
    @BeforeEach
    void installLogCapture() {
        Configurator.setLevel(FACTORY_LOGGER, Level.WARN);
        loggerContext = (LoggerContext) LogManager.getContext(false);
        appender = new CapturingAppender();
        appender.start();
        loggerContext.getConfiguration()
                     .getLoggerConfig(FACTORY_LOGGER)
                     .addAppender(appender, Level.WARN, null);
        loggerContext.updateLoggers();
    }

    @AfterEach
    void removeLogCapture() {
        loggerContext.getConfiguration()
                     .getLoggerConfig(FACTORY_LOGGER)
                     .removeAppender(appender.getName());
        appender.stop();
        loggerContext.updateLoggers();
    }

    private StorageConfig vaultConfig() {
        return StorageConfig.storageConfig(8L * 1024 * 1024,
                                           64L * 1024 * 1024,
                                           tempDir.resolve("vault-disk").toString(),
                                           tempDir.resolve("snapshots").toString(),
                                           1000,
                                           "60s",
                                           5,
                                           "",
                                           false);
    }

    private StorageFactory.StorageSetup setupWith(DHTClient client) {
        return StorageFactory.createAll(Map.of(INSTANCE, vaultConfig()), NODE_ID, Option.some(client), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir))
                             .onFailure(cause -> fail("createAll must succeed: " + cause.message()))
                             .unwrap()
                             .get(INSTANCE);
    }

    private static StorageFactory.DhtMarkerCheck checkOf(StorageFactory.StorageSetup setup) {
        return setup.dhtMarkerCheck()
                    .onEmpty(() -> fail("an instance with a DHT tier must carry a marker check"))
                    .unwrap();
    }

    private static BlockId blockId() {
        return BlockId.blockId(BLOCK).unwrap();
    }

    private static void assertServed(StorageFactory.StorageSetup setup, String why) {
        setup.instance()
             .get(blockId())
             .await(SETTLE_BOUND)
             .onFailure(cause -> fail(why + " -- read failed: " + cause.message()))
             .onSuccess(content -> assertThat(content.map(bytes -> new String(bytes, StandardCharsets.UTF_8)).or("<absent>")).as(why)
                                                                                     .isEqualTo(new String(BLOCK)));
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void verifyDhtMarker_admitsTierAndServesRead_whenFirstMarkerReadTimesOutThenSucceeds() {
        var client = new ScriptedDHTClient();
        var setup = setupWith(client);
        var check = checkOf(setup);

        client.seedBlock(check.dhtKeyPrefix(), blockId(), BLOCK);
        client.hangNextMarkerGets(1);

        StorageFactory.verifyDhtMarker(client, check, SHORT_ATTEMPT_TIMEOUT, FAST_BACKOFF, NEVER_STOPPED)
                      .await(SETTLE_BOUND)
                      .onFailure(cause -> fail("a marker read that timed out ONCE is transient and must not fail the check - "
                                               + cause.message()));

        assertThat(client.markerGets()).as("the timed-out attempt must be followed by exactly one successful retry")
                                       .isEqualTo(2);
        assertServed(setup, "after the retry verified the marker the DHT-backed read must be served");
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void verifyDhtMarker_keepsTierGatedAndRefusesRead_whileMarkerUnavailableBeyondAdmissionBound_thenServes() {
        var client = new ScriptedDHTClient();
        var setup = setupWith(client);
        var check = checkOf(setup);

        client.seedBlock(check.dhtKeyPrefix(), blockId(), BLOCK);
        client.quorumUnavailable(true);

        var verification = StorageFactory.verifyDhtMarker(client, check, SHORT_ATTEMPT_TIMEOUT, FAST_BACKOFF, NEVER_STOPPED);

        setup.instance()
             .get(blockId())
             .await(GATED_READ_BOUND)
             .onSuccess(_ -> fail("a DHT-backed read while the marker check is still pending must be REFUSED, never served"))
             .onFailure(cause -> assertThat(cause).as("the refusal must be the C1 admission error, not the transient DHT "
                                                      + "cause of a failed attempt leaking through a resolved gate")
                                                  .isInstanceOf(StorageError.TierNotAdmitted.class));

        assertThat(verification.isResolved()).as("the check must still be retrying after a full admission bound "
                                                  + "of unavailability -- it must not have failed")
                                              .isFalse();
        assertThat(check.readGate().isResolved()).as("no failed attempt may resolve the shared, first-writer-wins gate")
                                                 .isFalse();
        assertThat(client.markerGets()).as("the check must have kept retrying through the window")
                                       .isGreaterThan(1);
        assertThat(client.blockGets()).as("the refused read must never have reached the DHT")
                                      .isZero();
        assertThat(StorageFactory.pendingDhtAdmissions(Map.of(INSTANCE, setup))).containsExactly(INSTANCE);

        client.quorumUnavailable(false);

        verification.await(SETTLE_BOUND)
                    .onFailure(cause -> fail("once the ring answers, the check must verify - " + cause.message()));
        assertServed(setup, "once the check verified, the same DHT-backed read must be served");
        assertThat(StorageFactory.pendingDhtAdmissions(Map.of(INSTANCE, setup))).isEmpty();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void verifyDhtMarker_failsAfterOneAttemptWithoutRetry_whenMarkerPresentAndNoKeyring() {
        var client = new ScriptedDHTClient();
        var setup = setupWith(client);
        var check = checkOf(setup);

        client.seedMarker(check.dhtKeyPrefix(), "key-1");

        StorageFactory.verifyDhtMarker(client, check, SHORT_ATTEMPT_TIMEOUT, FAST_BACKOFF, NEVER_STOPPED)
                      .await(SETTLE_BOUND)
                      .onSuccess(_ -> fail("a marker present with no keyring must stay fatal"))
                      .onFailure(cause -> assertThat(cause).as("a definite mismatch must end the check with its own cause -- "
                                                               + "a CoreError.Timeout here means it was being retried")
                                                           .isInstanceOf(EncryptionError.EncryptedTierRequiresKeyring.class));

        assertThat(client.markerGets()).as("a definite refusal must never be retried").isEqualTo(1);
        check.readGate()
             .await(SHORT_ATTEMPT_TIMEOUT)
             .onSuccess(_ -> fail("#875: a refused check must resolve the gate to failure"))
             .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.EncryptedTierRequiresKeyring.class));
        setup.instance()
             .get(blockId())
             .await(SETTLE_BOUND)
             .onSuccess(_ -> fail("a refused DHT tier must refuse reads"))
             .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.EncryptedTierRequiresKeyring.class));
        StorageFactory.dhtAdmission(Map.of(INSTANCE, setup))
                      .await(SHORT_ATTEMPT_TIMEOUT)
                      .onSuccess(_ -> fail("a refused tier must never count as admitted for readiness"));
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void verifyDhtMarker_stopsRetryingAndLeavesTierRefused_whenOwnerStops() throws InterruptedException {
        var client = new ScriptedDHTClient();
        var setup = setupWith(client);
        var check = checkOf(setup);
        var stopped = new AtomicBoolean(false);

        client.quorumUnavailable(true);

        var verification = StorageFactory.verifyDhtMarker(client, check, SHORT_ATTEMPT_TIMEOUT, FAST_BACKOFF, stopped::get);

        awaitMarkerGets(client, 3);
        stopped.set(true);

        verification.await(SETTLE_BOUND)
                    .onSuccess(_ -> fail("a stopped owner's check must not report success"))
                    .onFailure(cause -> assertThat(cause).as("a stopped owner must end the loop with the abandon cause")
                                                         .isInstanceOf(EncryptionError.DhtMarkerCheckAbandoned.class));

        var attemptsAtStop = client.markerGets();

        Thread.sleep(300); // 15 backoff intervals: long enough for a still-running loop to attempt again

        assertThat(client.markerGets()).as("no attempt may run after the owner stopped (#642 zombie class)")
                                       .isEqualTo(attemptsAtStop);
        check.readGate()
             .await(SHORT_ATTEMPT_TIMEOUT)
             .onSuccess(_ -> fail("an abandoned check must never admit the tier"))
             .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.DhtMarkerCheckAbandoned.class));
    }

    /// The only test in this class that compiles against the pre-#1052 API: the two-argument production
    /// entry point, at the production backoff (first retry after ~1 s). Before the fix it fails on the
    /// first transient cause.
    @Test
    @Timeout(value = 60, unit = SECONDS)
    void verifyDhtMarker_retriesTransientFailure_throughProductionEntryPoint() {
        var client = new ScriptedDHTClient();
        var setup = setupWith(client);
        var check = checkOf(setup);

        client.failNextMarkerGetsWithoutQuorum(1);

        StorageFactory.verifyDhtMarker(client, check)
                      .await(timeSpan(20).seconds())
                      .onFailure(cause -> fail("a marker read that found no quorum ONCE is transient and must be retried, "
                                               + "not fail the check - " + cause.message()));

        assertThat(client.markerGets()).isEqualTo(2);
    }

    /// #1052 fix round 2 (SF-2): the per-attempt WARN is the operator's only signal while the check
    /// retries (`configuration.md`'s recovery step reads it), so it is pinned, not eyeballed: one WARN per
    /// failed attempt, none for the attempt that succeeds, each naming the instance and its attempt
    /// number in order. A WARN demoted to DEBUG, or one that drops the instance or the count, reds here.
    @Test
    @Timeout(value = 60, unit = SECONDS)
    void verifyDhtMarker_warnsOncePerFailedAttempt_namingInstanceAndAttemptNumber() {
        var client = new ScriptedDHTClient();
        var setup = setupWith(client);
        var check = checkOf(setup);

        client.failNextMarkerGetsWithoutQuorum(3);

        StorageFactory.verifyDhtMarker(client, check, SHORT_ATTEMPT_TIMEOUT, FAST_BACKOFF, NEVER_STOPPED)
                      .await(SETTLE_BOUND)
                      .onFailure(cause -> fail("three transient failures then success must verify - " + cause.message()));

        assertThat(client.markerGets()).as("PRECONDITION: three failed attempts and one successful retry").isEqualTo(4);

        var attemptWarnings = appender.warnings()
                                      .stream()
                                      .filter(message -> message.startsWith(ATTEMPT_WARN_PREFIX))
                                      .toList();

        assertThat(attemptWarnings).as("exactly one WARN per FAILED attempt: three, not four (the successful "
                                       + "retry is not a failure) and not zero (DEBUG is silent at the default level)")
                                   .hasSize(3);
        assertThat(attemptWarnings).allSatisfy(message -> assertThat(message).as("each WARN must name the storage instance")
                                                                              .contains("instance '" + INSTANCE + "'"));
        assertThat(attemptWarnings).as("each WARN must carry its 1-based attempt number, in order")
                                   .satisfiesExactly(first -> assertThat(first).startsWith(ATTEMPT_WARN_PREFIX + "1 for instance"),
                                                     second -> assertThat(second).startsWith(ATTEMPT_WARN_PREFIX + "2 for instance"),
                                                     third -> assertThat(third).startsWith(ATTEMPT_WARN_PREFIX + "3 for instance"));
    }

    /// #1052 fix round 2 (N-3): an instance IS present, so `allMatch(isEmpty)` is a real check, not a
    /// vacuous pass over an empty map -- what makes it carry no check is the absent DHT client, the
    /// same shape as every Ember boot.
    @Test
    void dhtAdmission_isAlreadyResolved_whenNoInstanceCarriesADhtTier() {
        var setups = StorageFactory.createAll(Map.of(INSTANCE, vaultConfig()), NODE_ID, Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir))
                                   .onFailure(cause -> fail("createAll must succeed: " + cause.message()))
                                   .unwrap();

        assertThat(setups).as("PRECONDITION: the instance must exist, or the all-match below is vacuous")
                          .containsKey(INSTANCE);
        assertThat(setups.values().stream().allMatch(setup -> setup.dhtMarkerCheck().isEmpty()))
                .as("PRECONDITION: no DHT client means no marker check on the instance")
                .isTrue();
        assertThat(StorageFactory.dhtAdmission(setups).isResolved())
                .as("with nothing to admit, self-ready must not be deferred at all (it runs synchronously)")
                .isTrue();
        assertThat(StorageFactory.pendingDhtAdmissions(setups)).isEmpty();
    }

    private static void awaitMarkerGets(ScriptedDHTClient client, int atLeast) throws InterruptedException {
        var deadline = System.nanoTime() + SETTLE_BOUND.nanos();

        while (client.markerGets() < atLeast && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(client.markerGets()).as("PRECONDITION: the check must be retrying before the owner stops")
                                       .isGreaterThanOrEqualTo(atLeast);
    }

    /// Captures WARN events emitted by `StorageFactory` so the #1052 per-attempt line can be asserted.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("storage-factory-dht-marker-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        List<String> warnings() {
            return List.copyOf(messages);
        }
    }

    /// In-memory `DHTClient` whose marker-key gets can be scripted to hang, to fail fast without quorum, or
    /// to keep failing until released. It counts marker gets and block gets separately. Duplicates the
    /// store shape of `StorageFactoryEncryptionTest.InMemoryDHTClient` for the reason recorded there:
    /// package-private test doubles are not reusable across files.
    private static final class ScriptedDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();
        private final AtomicInteger hangingMarkerGets = new AtomicInteger();
        private final AtomicInteger noQuorumMarkerGets = new AtomicInteger();
        private final AtomicBoolean quorumUnavailable = new AtomicBoolean();
        private final AtomicInteger markerGets = new AtomicInteger();
        private final AtomicInteger blockGets = new AtomicInteger();

        void seedBlock(String keyPrefix, BlockId id, byte[] content) {
            store.put(keyPrefix + "/" + id.hexString(), content);
        }

        void seedMarker(String keyPrefix, String keyId) {
            store.put(keyPrefix + "/" + EncryptingStorageTier.MARKER_FILE_NAME, keyId.getBytes(StandardCharsets.UTF_8));
        }

        void hangNextMarkerGets(int count) {
            hangingMarkerGets.set(count);
        }

        void failNextMarkerGetsWithoutQuorum(int count) {
            noQuorumMarkerGets.set(count);
        }

        void quorumUnavailable(boolean unavailable) {
            quorumUnavailable.set(unavailable);
        }

        int markerGets() {
            return markerGets.get();
        }

        int blockGets() {
            return blockGets.get();
        }

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            var name = keyString(key);

            if (!name.endsWith(EncryptingStorageTier.MARKER_FILE_NAME)) {
                blockGets.incrementAndGet();
                return Promise.success(option(store.get(name)));
            }

            markerGets.incrementAndGet();

            if (takeOne(hangingMarkerGets)) {
                return Promise.promise(); // never resolves -- only the attempt timeout ends it
            }
            if (takeOne(noQuorumMarkerGets) || quorumUnavailable.get()) {
                return DHTError.quorumNotReached(2, 1).promise();
            }

            return Promise.success(option(store.get(name)));
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            store.put(keyString(key), value);
            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(store.remove(keyString(key)) != null);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(store.containsKey(keyString(key)));
        }

        /// Never invoked on the storage-tier path; matches the sibling stubs' established shape.
        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }

        private static boolean takeOne(AtomicInteger remaining) {
            return remaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
        }

        private static String keyString(byte[] key) {
            return new String(key, StandardCharsets.UTF_8);
        }
    }
}
