// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.environment.*;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.RefusalKind;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #3754 C1, lane (a): a leader-only external effect (terminate, and the create's dispatch) is issued only in the continuation of an
/// accepted leader transaction this node committed for it, and only while ONE accessor, [CapacityControlledLifecycle#activation],
/// still pairs a present leader with the generation read at submit. Every test drives the events in production order: the hook runs
/// inside the `apply` function, exactly where consensus would let the world move between the submit and its continuation.
@Timeout(20)
class CapacityFenceTest {
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId SUCCESSOR = new NodeId("successor");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private static final SourceName EAST = SourceName.sourceName("east").unwrap();
    private static final NodeId NODE = new NodeId("first");
    private static final TimeSpan FIVE_SECONDS = TimeSpan.timeSpan(5).seconds();
    private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final String EAST_TOML = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.east]
        type = "forge"
        [source.east.core]
        count = 3
        """;

    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger terminates = new AtomicInteger();
    private final AtomicInteger listings = new AtomicInteger();
    private final AtomicBoolean leading = new AtomicBoolean(true);
    private final AtomicLong nano = new AtomicLong(1_000_000_000L);
    private final AtomicLong epoch = new AtomicLong(1_700_000_000_000L);
    /// Run just before / just after the witness transaction (the empty-mutation leader transaction) is applied, and just after the
    /// reservation of a create is committed.
    private Runnable beforeWitness = () -> {};
    private Runnable afterWitness = () -> {};
    private Runnable afterReserve = () -> {};

    private final NodeLifecycleManager provider = new NodeLifecycleManager() {
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            creates.incrementAndGet();
            return Causes.<InstanceInfo>cause("Provider timed out after accepting request").promise();
        }
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec, String binding) { return provisionNode(spec); }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source, String binding) { return Promise.success(List.of()); }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source) { return Promise.success(List.of()); }
        @Override public Promise<Unit> terminateNode(NodeId node, SourceName source, String binding) { return terminateNode(node); }
        @Override public Promise<Unit> terminateNode(NodeId node) {
            terminates.incrementAndGet();
            return Promise.unitPromise();
        }
        @Override public Promise<ActionResult> executeAction(NodeAction action) { return Causes.<ActionResult>cause("unused").promise(); }
        @Override public Result<String> sourceBinding(SourceName source) { return Result.success("binding"); }
        @Override public boolean isCloudManaged() { return true; }
        @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
            listings.incrementAndGet();
            return Promise.success(List.of());
        }
        @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter, SourceName source, String binding) {
            listings.incrementAndGet();
            return Promise.success(List.of());
        }
    };

    private CapacityControlledLifecycle lifecycle = lifecycleWith(FIVE_SECONDS);

    private CapacityControlledLifecycle lifecycleWith(TimeSpan applyTimeout) {
        return CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store, this::apply, leading::get, () -> 5,
            SourceProfile.DEFAULT_REPLACEMENT_CEILING, epoch::get, nano::get, applyTimeout);
    }

    private Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
        var witness = commands.stream().anyMatch(CapacityFenceTest::isWitness);
        var reserves = commands.stream().anyMatch(CapacityFenceTest::reservesCapacity);

        if (witness) {
            beforeWitness.run();
        }
        var results = store.process(store.createBatch(commands));

        if (witness) {
            afterWitness.run();
        }

        if (reserves) {
            afterReserve.run();
        }
        return Promise.success(results);
    }

    private static boolean isWitness(KVCommand<?> command) {
        return command instanceof KVCommand.LeaderTransaction<?, ?> transaction && transaction.mutations().isEmpty();
    }

    private static boolean reservesCapacity(KVCommand<?> command) {
        return command instanceof KVCommand.LeaderTransaction<?, ?> transaction
               && transaction.mutations().stream().anyMatch(mutation -> mutation.key() instanceof AetherKey.CapacityReservationKey);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private void initialize(int allocated) {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(EAST_TOML), "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        seed(new KVCommand.LeaderTransaction<>(AetherKey.CapacityLedgerKey.INSTANCE, UUID.randomUUID().toString(), LEADER, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(AetherKey.CapacityLedgerKey.INSTANCE, Option.none(),
                Option.some(new AetherValue.CapacityLedgerValue(allocated, 1, true))))));
    }

    private AetherValue.CapacityReservationValue observedReservation() {
        return new AetherValue.CapacityReservationValue("east", "binding", "worker", AetherValue.CapacityReservationPhase.OBSERVED);
    }

    /// A terminable node: initialized fleet, one OBSERVED reservation of `NODE`.
    private void terminable() {
        initialize(1);
        var key = new AetherKey.CapacityReservationKey(NODE);

        seed(new KVCommand.LeaderTransaction<>(key, "observed", LEADER, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.none(), Option.some(observedReservation())))));
    }

    private void releaseReservation() {
        var key = new AetherKey.CapacityReservationKey(NODE);

        seed(new KVCommand.LeaderTransaction<>(key, "released", LEADER, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.some(observedReservation()), Option.none()))));
    }

    private void replaceLeader(LeaderValue next) { seed(new KVCommand.Put<>(LeaderKey.INSTANCE, next)); }

    /// What the instance observes when leadership is lost and regained between two of its own reads of the accessor.
    private void observedLossAndRegain() {
        leading.set(false);
        lifecycle.activation();
        leading.set(true);
        lifecycle.activation();
    }

    private ProvisionSpec spec(String node) {
        return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "size", "worker",
            ProvisionContext.forBootstrap(ClusterName.clusterName("test").unwrap(), "worker", EAST, node)).unwrap();
    }

    private AetherValue.CapacityLedgerValue ledger() {
        return store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, AetherValue.CapacityLedgerValue.class).unwrap();
    }

    private Result<Unit> terminate() {
        return lifecycle.terminateNode(NODE, EAST, "binding").await();
    }

    private static void assertFailsWith(Result<Unit> outcome, Class<? extends FenceFailure> type) {
        assertThat(outcome.isFailure()).as("the terminate failed: %s", outcome).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isInstanceOf(type));
    }

    // ---- TA1, TA1p, TA1w, TA1g: the terminate ----

    /// TA1: the store names another leader when the witness is applied: the applier refuses it with LEADER, the provider is never asked,
    /// and the terminate FAILS with the typed cause.
    @Test
    void terminate_underAStoreNamingAnotherLeader_failsWithLeaderRefusal_andCallsNoProvider() {
        terminable();
        beforeWitness = () -> replaceLeader(new LeaderValue(SUCCESSOR, 2));

        var outcome = terminate();

        assertFailsWith(outcome, FenceFailure.LeadershipNotWitnessed.class);
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(FenceFailure.LeadershipNotWitnessed.leadershipNotWitnessed(RefusalKind.LEADER)));
        assertThat(terminates.get()).as("no provider call").isZero();
    }

    /// TA1p: stored leader, unchanged reservation: exactly ONE provider call, and the guard (the reservation exactly as `records()` read it)
    /// is accepted, which pins that `records().reservation` is the stored value the witness names.
    @Test
    void terminate_underTheStoredLeader_withAnUnchangedReservation_callsTheProviderExactlyOnce() {
        terminable();

        var outcome = terminate();

        assertThat(outcome.isSuccess()).as("terminate: %s", outcome).isTrue();
        assertThat(terminates.get()).as("exactly one provider call").isEqualTo(1);
    }

    /// TA1w: a release committed between the read and the witness: the guard is unequal, the applier refuses with WITNESS, zero calls.
    @Test
    void terminate_whenTheReservationIsReleasedBetweenTheReadAndTheWitness_failsWithWitnessRefusal_andCallsNoProvider() {
        terminable();
        beforeWitness = this::releaseReservation;

        var outcome = terminate();

        assertFailsWith(outcome, FenceFailure.LeadershipNotWitnessed.class);
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(FenceFailure.LeadershipNotWitnessed.leadershipNotWitnessed(RefusalKind.WITNESS)));
        assertThat(terminates.get()).as("no provider call").isZero();
    }

    /// TA1g: leadership lost and regained (both observed) between the submit and the accept: the witness is accepted, the continuation
    /// re-reads the accessor, sees another generation, and refuses with ActivationChanged: zero calls.
    @Test
    void terminate_whenLeadershipIsLostAndRegainedBetweenSubmitAndAccept_failsWithActivationChanged_andCallsNoProvider() {
        terminable();
        afterWitness = this::observedLossAndRegain;

        var outcome = terminate();

        assertFailsWith(outcome, FenceFailure.ActivationChanged.class);
        assertThat(terminates.get()).as("no provider call").isZero();
    }

    // ---- TA1t, TA1c: the elapsed check ----

    /// TA1t: an injected monotonic clock advanced past the configured `applyTimeout` (5 s) between submit and the continuation.
    @Test
    void terminate_whenTheElapsedTimeExceedsTheConfiguredApplyTimeout_failsWithWitnessExpired_andCallsNoProvider() {
        terminable();
        afterWitness = () -> nano.addAndGet(6 * SECOND_NANOS);

        var outcome = terminate();

        assertFailsWith(outcome, FenceFailure.WitnessExpired.class);
        assertThat(terminates.get()).as("no provider call").isZero();
    }

    /// TA1t, second case: the timeout is read from configuration, not a constant. With the default (30 s) a 6 s delay is healthy; with 5 s
    /// the same 6 s is refused (above), and 31 s is refused under the default.
    @Test
    void terminate_underTheDefaultApplyTimeout_acceptsSixSeconds_andRefusesThirtyOne() {
        terminable();
        lifecycle = lifecycleWith(ProtocolConfig.DEFAULT_APPLY_TIMEOUT);
        afterWitness = () -> nano.addAndGet(6 * SECOND_NANOS);

        assertThat(terminate().isSuccess()).as("6 s under a 30 s timeout").isTrue();
        assertThat(terminates.get()).isEqualTo(1);

        afterWitness = () -> nano.addAndGet(31 * SECOND_NANOS);
        var late = terminate();

        assertFailsWith(late, FenceFailure.WitnessExpired.class);
        assertThat(terminates.get()).as("the late witness issued nothing").isEqualTo(1);
    }

    /// TA1t, control: inside the timeout, and exactly at it (the check is a strict `>`), the effect proceeds: the check is not a refusal
    /// that always fires.
    @Test
    void terminate_whenTheElapsedTimeIsWithinOrExactlyAtTheApplyTimeout_callsTheProvider() {
        terminable();
        afterWitness = () -> nano.addAndGet(4 * SECOND_NANOS);
        assertThat(terminate().isSuccess()).as("4 s of 5 s").isTrue();
        assertThat(terminates.get()).isEqualTo(1);

        afterWitness = () -> nano.addAndGet(5 * SECOND_NANOS);
        assertThat(terminate().isSuccess()).as("exactly 5 s of 5 s").isTrue();
        assertThat(terminates.get()).isEqualTo(2);
    }

    /// TA1c: the epoch clock (`clock`) stepped backwards by an hour while the monotonic clock passes the timeout: the check still refuses.
    @Test
    void elapsedCheck_ignoresTheEpochClockSteppedBackwards() {
        terminable();
        afterWitness = () -> {
            epoch.addAndGet(-TimeUnit.HOURS.toMillis(1));
            nano.addAndGet(6 * SECOND_NANOS);
        };

        var outcome = terminate();

        assertFailsWith(outcome, FenceFailure.WitnessExpired.class);
        assertThat(terminates.get()).as("no provider call").isZero();
    }

    /// TA1c, the other direction: the epoch clock stepped forwards by an hour while no monotonic time passes: the effect is not refused.
    @Test
    void elapsedCheck_ignoresTheEpochClockSteppedForwards() {
        terminable();
        afterWitness = () -> epoch.addAndGet(TimeUnit.HOURS.toMillis(1));

        var outcome = terminate();

        assertThat(outcome.isSuccess()).as("terminate: %s", outcome).isTrue();
        assertThat(terminates.get()).isEqualTo(1);
    }

    // ---- TA6, TA6c, TA7: the create's dispatch ----

    /// TA6: leadership lost and regained (both observed) between the reservation commit and the dispatch: the provider is never asked, and
    /// the reservation, which is committed, is released through the refusal path once reconciled.
    @Test
    void create_whenLeadershipIsRegainedBetweenReserveAndDispatch_abandonsTheDispatch_andReleasesTheReservation() {
        initialize(0);
        afterReserve = this::observedLossAndRegain;
        var key = new AetherKey.CapacityReservationKey(NODE);

        var outcome = lifecycle.provisionNode(spec("first")).await();

        assertThat(outcome.isFailure()).isTrue();
        assertThat(creates.get()).as("no provider create").isZero();
        lifecycle.reconcileInventory().await().unwrap();
        lifecycle.reconcileRefusals().await().unwrap();
        assertThat(store.get(key).isEmpty()).as("the reservation is released by the refusal path").isTrue();
        assertThat(ledger().allocated()).isZero();
        assertThat(lifecycle.allocatedNodes("worker")).isEmpty();
    }

    /// TA6c: the first create after a regain, with the inventory cached and no earlier read of the accessor since the regain: the
    /// reservation commit's own read observes the regain, so the generation it carries is the new one and the create PROCEEDS.
    @Test
    void create_firstAfterARegain_withTheInventoryCached_proceedsWithOneProviderCreate() {
        initialize(0);
        lifecycle.provisionNode(spec("warm")).await();
        var listed = listings.get();
        assertThat(creates.get()).as("the warm-up create").isEqualTo(1);
        leading.set(false);
        lifecycle.activation();
        leading.set(true);

        lifecycle.provisionNode(spec("first")).await();

        assertThat(listings.get()).as("control: the inventory was cached, no listing was taken").isEqualTo(listed);
        assertThat(creates.get()).as("one more provider create").isEqualTo(2);
    }

    /// TA7: a same-leader re-commit (a new view sequence, no lost leadership) between the reservation and the dispatch is not a change of
    /// activation: the create proceeds.
    @Test
    void create_whenTheSameLeaderIsRecommittedBetweenReserveAndDispatch_proceeds() {
        initialize(0);
        afterReserve = () -> replaceLeader(new LeaderValue(CORE, 2));

        lifecycle.provisionNode(spec("first")).await();

        assertThat(creates.get()).as("one provider create").isEqualTo(1);
    }

    // ---- TA9: the accessor is one monitor step ----

    /// A flag whose lose-to-gain edge parks the observing thread, inside the accessor, between the edge and the generation increment.
    private static final class ParkingFlag extends AtomicBoolean {
        final CountDownLatch parked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean parkOnce = new AtomicBoolean();

        ParkingFlag() { super(false); }

        @Override
        public boolean getAndSet(boolean next) {
            var previous = super.getAndSet(next);

            if (next && !previous && parkOnce.compareAndSet(false, true)) {
                parked.countDown();
                awaitQuietly(release);
            }
            return previous;
        }

        private static void awaitQuietly(CountDownLatch latch) {
            try {
                latch.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private CapacityControlledLifecycle withLeading(CapacityControlledLifecycle base, AtomicBoolean flag) {
        return new CapacityControlledLifecycle(base.delegate(), base.self(), base.store(), base.apply(), base.activeLeader(), base.limit(),
            base.initializing(), base.inventoried(), base.inventoryReconciling(), base.activeSources(), base.inventoryCursors(),
            base.undispatched(), base.dispatchStamps(), base.dispatchMisses(), base.unmatchedSince(), flag, base.leadershipGeneration(),
            base.warningSink(), base.clock(), base.ceiling(), base.nanoClock(), base.applyTimeout());
    }

    /// TA9, deterministic interleaving: thread A takes the lose-to-gain edge and is parked before it counts the gain. Thread B then reads
    /// the accessor. `activation()` is ONE monitor step, so B cannot finish while A is parked inside it; B's answer, when it arrives, pairs
    /// "present" with a generation that includes A's gain. Without the monitor B finishes first and answers "present" with generation 0.
    @Test
    void activation_isOneMonitorStep_aReaderNeverPairsPresentWithAGenerationOlderThanTheGainItFollows() throws Exception {
        initialize(0);
        var flag = new ParkingFlag();
        var parking = withLeading(lifecycle, flag);
        var second = new AtomicReference<Option<Activation>>(Option.none());
        var secondDone = new CountDownLatch(1);
        var first = Thread.ofPlatform().start(parking::activation);

        assertThat(flag.parked.await(5, TimeUnit.SECONDS)).as("thread A is parked on the gain edge").isTrue();
        var reader = Thread.ofPlatform().start(() -> {
            second.set(parking.activation());
            secondDone.countDown();
        });

        var finishedWhileParked = secondDone.await(250, TimeUnit.MILLISECONDS);
        flag.release.countDown();
        first.join(5_000);
        reader.join(5_000);

        assertThat(finishedWhileParked).as("B must wait for A's whole step").isFalse();
        assertThat(second.get().isPresent()).isTrue();
        assertThat(second.get().map(Activation::generation).or(-1L)).as("present is never paired with a generation older than the gain").isGreaterThanOrEqualTo(1L);
    }

    /// TA9, smoke: a bounded randomized run (not the evidence; the deterministic test above is). One thread flips the leadership flag, the
    /// other reads the accessor: a present answer always carries a generation of at least 1, because every present follows a gain edge.
    @Test
    void activation_underConcurrentFlipping_neverAnswersPresentWithGenerationZero() throws Exception {
        initialize(0);
        var bad = new AtomicInteger();
        var flipper = Thread.ofPlatform().start(() -> {
            for (var round = 0; round < 20_000; round++) {
                leading.set(round % 2 == 0);
            }
        });
        var reader = Thread.ofPlatform().start(() -> {
            for (var round = 0; round < 20_000; round++) {
                lifecycle.activation().onPresent(activation -> {
                    if (activation.generation() < 1) {
                        bad.incrementAndGet();
                    }
                });
            }
        });

        flipper.join(15_000);
        reader.join(15_000);

        assertThat(bad.get()).as("present answers carrying a generation older than the gain").isZero();
    }
}
