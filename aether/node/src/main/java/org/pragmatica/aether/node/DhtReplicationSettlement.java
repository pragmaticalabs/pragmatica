// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.deployment.cluster.ClusterReplication;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtReplicationChangeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtReplicationReportKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtReplicationChangeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtReplicationReportValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtReplicationStage;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #1777, CTO ruling R1b: a live change of the DHT's `[replication]` factors switches the cluster to the new quorums
/// ONCE, cluster-wide, by a committed fact — never per node.
///
/// The committed record is [DhtReplicationChangeValue] under [DhtReplicationChangeKey]. It moves through three stages,
/// each a compare-and-set leader transaction expecting the exact record it replaces, so a stale leader, a transition
/// computed from a superseded change, or a report for an older version can never advance a newer one:
///
/// 1. [DhtReplicationStage#APPLYING] — committed when the leader observes the committed configuration change the
///    factors. Every node keeps W_t = max(W_old, W_new) and R_t = max(R_old, R_new).
/// 2. [DhtReplicationStage#WRITERS_SWITCHED] — every expected member reported the change applied
///    ([DhtReplicationReportValue#appliedVersion] at or above the change version). From here every write is acked at
///    W_t or higher. Each core then catches up again in a pass that began after this point ([DHTNode#writersSwitched]),
///    which copies every write a laggard acked at W_old before it applied the change.
/// 3. [DhtReplicationStage#SETTLED] — every expected core reported that pass complete. The new quorums apply.
///
/// Why the middle stage: "every core caught up" and "every writer applied" are not enough in either order. A laggard
/// writing at W_old after a core's catch-up pass leaves the value on one replica that no completed pass has copied.
///
/// **Who is expected.** The DHT replicas this node's ring names (cores), the leader's membership view of workers, and
/// every member that has filed a report — minus every member the leader's membership view holds `Dead`. This is a soft
/// view, not a committed roster, and it decides only WHEN a change settles: a wrong roster delays or hastens the settle.
/// Safety comes from the replication-change fence (CTO ruling R1c): every put carries the writer's applied change, and a
/// replica that has applied a newer one refuses it, so a writer the roster missed cannot land an old-quorum write after
/// the settle.
/// A member that never reports holds the change unsettled: readers stay on R_t, which is safe and never a false
/// absent, at the cost of the stricter read quorum (an unavailable read rather than a wrong one). After
/// [#OVERDUE_AFTER_MS] the leader commits the change overdue and the operator gets `DHT_REPLICATION_UNSETTLED`; it
/// clears with `DHT_REPLICATION_SETTLED` when the change settles or is superseded.
///
/// **No timers.** The leader evaluates on the commits that can advance a change (the configuration, the change record,
/// any report), on leadership gain, and on the cluster-sync pong cadence it already receives — the last is what notices
/// that time has passed and that a member's departure is now committed. An evaluation drops the reports of departed
/// members, so a stale report never holds a later change.
public interface DhtReplicationSettlement {
    Logger LOG = LoggerFactory.getLogger(DhtReplicationSettlement.class);
    /// The operator-attention bound. A change settles after every member's next configuration notification (cores) or
    /// projection poll (workers, 1 s) plus one catch-up pass of every core (1 s rounds); it is seconds when the cluster
    /// is healthy. Five minutes is well past any of that and past the membership's own confirmation of a dead member —
    /// the usual reason a change stays unsettled, which clears by itself once the member is confirmed Dead. Beyond it,
    /// something is holding the stricter read quorum in place that an operator should look at: a member alive enough
    /// to stay counted but not reporting, or a core whose catch-up cannot complete.
    long OVERDUE_AFTER_MS = TimeUnit.MINUTES.toMillis(5);
    /// #2553: a node whose own store no longer holds its report, while a change is open, files it again no sooner than this
    /// after its last filing; each further re-file waits twice as long, up to [#REFILE_BACKOFF_MAX_MS]. Seeing the report
    /// present resets it. These are guesses, not measurements: 1 s is far above the 100 ms retry tick (no churn at the tick)
    /// and below the 5 min overdue mark; 60 s bounds a node the leader keeps dropping to two commits a minute.
    long REFILE_BACKOFF_MIN_MS = 1_000L;
    long REFILE_BACKOFF_MAX_MS = 60_000L;

    String UNSETTLED_REASON = "unsettled for longer than " + TimeUnit.MILLISECONDS.toMinutes(OVERDUE_AFTER_MS)
                            + " minutes";

    // ---- pure decisions ---------------------------------------------------------------------------------------------
    /// What the leader commits for a committed configuration (version `configVersion`, factors `rf`/`cf`): a baseline
    /// SETTLED record when there is none yet, a new APPLYING change when the factors differ from the committed
    /// change's, and nothing otherwise. A configuration version at or below the committed change's is stale.
    static Option<DhtReplicationChangeValue> nextChange(Option<DhtReplicationChangeValue> committed,
                                                        long configVersion,
                                                        int rf,
                                                        int cf,
                                                        long now) {
        // the baseline is not a change: it carries no version, so it fences no writer (#1777 R1c) — a writer that has
        // seen no change record yet stamps the same NO_CHANGE
        if (committed.isEmpty()) {
            return Option.some(new DhtReplicationChangeValue(DHTNode.NO_CHANGE,
                                                             rf,
                                                             cf,
                                                             cf,
                                                             readQuorum(rf, cf),
                                                             rf,
                                                             DhtReplicationStage.SETTLED,
                                                             now,
                                                             false));
        }

        var previous = committed.unwrap();

        if (configVersion <= previous.version() || (previous.replicationFactor() == rf && previous.confirmationFactor() == cf)) {
            return Option.none();
        }
        // an unsettled predecessor's floor and sources carry over: consecutive changes keep the strictest of all
        var carried = !previous.settled();
        var floorWrite = Math.max(previous.confirmationFactor(),
                                  carried
                                  ? previous.floorWriteQuorum()
                                  : 0);
        var floorRead = Math.max(readQuorum(previous.replicationFactor(), previous.confirmationFactor()),
                                 carried
                                 ? previous.floorReadQuorum()
                                 : 0);
        var sources = Math.max(Math.max(previous.replicationFactor(), rf),
                               carried
                               ? previous.sourceReplicationFactor()
                               : 0);

        return Option.some(new DhtReplicationChangeValue(configVersion,
                                                         rf,
                                                         cf,
                                                         floorWrite,
                                                         floorRead,
                                                         sources,
                                                         DhtReplicationStage.APPLYING,
                                                         now,
                                                         false));
    }

    /// #2545: whether the leader would read the same thing from `desired` as from `last`, the report this node filed. They
    /// are the same unless the caught-up version or the replica flag differ, or `desired.appliedVersion` reaches the
    /// version of the open change (`openChangeVersion`, or [DHTNode#NO_CHANGE]) that `last.appliedVersion` was below:
    /// the leader compares `appliedVersion` with that version and with nothing else. A change that arrives, or is named,
    /// later finds `appliedVersion` already above it, and files then.
    static boolean readsAsFiled(DhtReplicationReportValue last,
                                DhtReplicationReportValue desired,
                                long openChangeVersion) {
        return last.caughtUpVersion() == desired.caughtUpVersion()
               && last.replica() == desired.replica()
               && last.appliedVersion() <= desired.appliedVersion()
               && (openChangeVersion <= last.appliedVersion() || openChangeVersion > desired.appliedVersion());
    }

    /// The next stage of `change` given the members' reports, or nothing when it cannot advance. `cores` are the DHT
    /// replicas, `workers` the other writers; a reporter whose report says `replica` counts as a core too. Members with a
    /// `Dead` in the leader's membership view are not waited for. Also commits `overdue` once the change has been unsettled for longer than
    /// [#OVERDUE_AFTER_MS]; settling clears it.
    static Option<DhtReplicationChangeValue> advance(DhtReplicationChangeValue change,
                                                     Map<NodeId, DhtReplicationReportValue> reports,
                                                     Set<NodeId> cores,
                                                     Set<NodeId> workers,
                                                     Predicate<NodeId> departed,
                                                     long now) {
        if (change.settled()) {
            return Option.none();
        }

        var next = switch (change.stage()) {
            case APPLYING -> allApplied(change, reports, expectedMembers(reports, cores, workers, departed))
                             ? change.withStage(DhtReplicationStage.WRITERS_SWITCHED)
                             : change;
            case WRITERS_SWITCHED -> allCaughtUp(change, reports, expectedReplicas(reports, cores, departed))
                                     ? change.withStage(DhtReplicationStage.SETTLED).withOverdue(false)
                                     : change;
            case SETTLED, UNKNOWN -> change;
        };
        var flagged = !next.settled() && !next.overdue() && now - next.since() > OVERDUE_AFTER_MS
                      ? next.withOverdue(true)
                      : next;

        return flagged.equals(change)
               ? Option.none()
               : Option.some(flagged);
    }

    private static boolean allApplied(DhtReplicationChangeValue change,
                                      Map<NodeId, DhtReplicationReportValue> reports,
                                      Set<NodeId> expected) {
        return expected.stream()
                       .allMatch(member -> Option.option(reports.get(member))
                                                 .map(report -> report.appliedVersion() >= change.version())
                                                 .or(false));
    }

    private static boolean allCaughtUp(DhtReplicationChangeValue change,
                                       Map<NodeId, DhtReplicationReportValue> reports,
                                       Set<NodeId> expected) {
        return expected.stream()
                       .allMatch(member -> Option.option(reports.get(member))
                                                 .map(report -> report.caughtUpVersion() >= change.version())
                                                 .or(false));
    }

    private static Set<NodeId> expectedMembers(Map<NodeId, DhtReplicationReportValue> reports,
                                               Set<NodeId> cores,
                                               Set<NodeId> workers,
                                               Predicate<NodeId> departed) {
        var expected = new HashSet<NodeId>(cores);

        expected.addAll(workers);
        expected.addAll(reports.keySet());
        expected.removeIf(departed);

        return expected;
    }

    private static Set<NodeId> expectedReplicas(Map<NodeId, DhtReplicationReportValue> reports,
                                                Set<NodeId> cores,
                                                Predicate<NodeId> departed) {
        var expected = new HashSet<NodeId>(cores);

        reports.forEach((member, report) -> {
            if (report.replica()) {
                expected.add(member);
            }
        });
        expected.removeIf(departed);

        return expected;
    }

    /// The operator event a committed transition of the change record calls for: entering the overdue condition, or
    /// leaving it — by settling, or by being superseded by a newer change. Derived from the committed old and new values,
    /// so every node derives the same event and the cluster-events owner gate publishes it AT MOST ONCE (missed if the owner
    /// cannot publish at that moment); the committed flag is
    /// the per-change dedupe, which survives a leader change.
    static Option<OperationalEvent> transition(Option<DhtReplicationChangeValue> before,
                                               DhtReplicationChangeValue after) {
        var wasOverdue = before.filter(DhtReplicationChangeValue::overdue);

        if (wasOverdue.isPresent()) {
            var left = wasOverdue.unwrap();

            if (left.version() != after.version()) {
                return Option.some(settledEvent(left, "superseded by change " + after.version()));
            }

            return after.overdue()
                   ? Option.none()
                   : Option.some(settledEvent(left, "settled"));
        }

        return after.overdue()
               ? Option.some(OperationalEvent.DhtReplicationUnsettled.dhtReplicationUnsettled(after.version(),
                                                                                              after.replicationFactor(),
                                                                                              after.confirmationFactor(),
                                                                                              after.stage().name(),
                                                                                              after.since(),
                                                                                              UNSETTLED_REASON))
               : Option.none();
    }

    private static OperationalEvent settledEvent(DhtReplicationChangeValue change, String reason) {
        return OperationalEvent.DhtReplicationSettled.dhtReplicationSettled(change.version(),
                                                                            change.replicationFactor(),
                                                                            change.confirmationFactor(),
                                                                            change.since(),
                                                                            reason);
    }

    /// Bring this node's DHT in line with the committed change: hold its floor while it is unsettled, run the
    /// writers-switched catch-up once the writers switched, and drop the floor once it settled. FULL replication has no
    /// placement and no quorums to move.
    @Contract
    static void applyCommitted(DHTNode dhtNode, DHTConfig declared, DhtReplicationChangeValue change) {
        if (declared.isFullReplication()) {
            return;
        }

        declared.withFactors(change.replicationFactor(),
                             change.confirmationFactor())
                .onSuccess(factors -> applyAtFactors(dhtNode, factors, change))
                .onFailure(cause -> LOG.error("DHT replication change {} carries refused factors: {}",
                                              change.version(),
                                              cause.message()));
    }

    private static void applyAtFactors(DHTNode dhtNode, DHTConfig factors, DhtReplicationChangeValue change) {
        // R1c: a node already on the change's factors stamps and fences its writes at the committed version
        dhtNode.adoptReplicationChange(change.version(), factors);
        switch (change.stage()) {
            case SETTLED -> dhtNode.settleReplicationChange(change.version(), factors);
            case WRITERS_SWITCHED -> {
                dhtNode.holdReplicationChange(change.version(), change.floorWriteQuorum(), change.floorReadQuorum());
                dhtNode.writersSwitched(change.version(), change.sourceReplicationFactor());
            }
            case APPLYING, UNKNOWN -> dhtNode.holdReplicationChange(change.version(),
                                                                    change.floorWriteQuorum(),
                                                                    change.floorReadQuorum());
        }
    }

    static int readQuorum(int rf, int cf) {
        return rf - cf + 1;
    }

    // ---- the running driver -----------------------------------------------------------------------------------------
    /// The configuration version this node applied ([DhtReplicationReportValue#appliedVersion]). It files a report only
    /// when that can change what the leader reads (see [#report]).
    @Contract
    void applied(long configVersion);

    /// [#applied(long)] with a second source of the open change: a worker's projection carries it, besides the change record
    /// the node's own store may already return. `openChangeVersion` is the version of the unsettled change the projection
    /// names, or [DHTNode#NO_CHANGE]; the larger of it and the record's wins.
    @Contract
    default void applied(long configVersion, long openChangeVersion) {
        applied(configVersion);
    }

    /// A committed configuration: the leader commits the change it carries, if any.
    @Contract
    void onConfigCommitted(AetherValue.ClusterConfigValue config);

    /// A committed change record (every node): apply it to the DHT, announce its transition, report.
    @Contract
    void onChangeCommitted(Option<DhtReplicationChangeValue> before, DhtReplicationChangeValue after);

    /// Re-read the committed change and apply it (state restore, a worker's projection).
    @Contract
    void reapply();

    /// The leader advances the committed change when the reports allow it.
    @Contract
    void evaluate();

    /// File this node's report when it differs from the one it last filed in a way the leader can read. The leader reads
    /// `appliedVersion` only as "at or above the version of the open change", so a newer applied version alone is not
    /// filed until an open change names a version it now reaches (#2545); a changed caught-up version or replica flag
    /// always is. Called on every event that changes it, and on the worker-metadata tick every node already runs, which
    /// retries a report whose submission failed (a worker before its forwarding path is up, a core during a quorum
    /// loss): without a retry, one lost report would hold the change unsettled until an operator noticed. The report has
    /// no other liveness role: the leader waits for members by its membership view and the ring, not by report age.
    @Contract
    void report();

    /// How many times this node filed its report AGAIN because its own store no longer held it while a change was open
    /// (#2553). Zero for a node whose report was never dropped; a node the leader keeps dropping shows a slow trickle.
    default long refileCount() {
        return 0L;
    }

    /// The inputs of a running settlement. `submit` applies commands through this node's cluster path (a worker
    /// forwards to a core). `departed` answers whether the leader's membership view holds a member `Dead`.
    record Inputs(NodeId self,
                  boolean replica,
                  KVStore<AetherKey, AetherValue> kvStore,
                  DHTNode dhtNode,
                  DHTConfig declared,
                  Supplier<Boolean> isLeader,
                  Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> submit,
                  Supplier<Set<NodeId>> workers,
                  Predicate<NodeId> departed,
                  Consumer<OperationalEvent> events,
                  LongSupplier clock,
                  BooleanSupplier consensusCaughtUp,
                  BooleanSupplier replicaReady) {}

    static DhtReplicationSettlement dhtReplicationSettlement(Inputs inputs) {
        record running(Inputs inputs,
                       AtomicLong appliedVersion,
                       AtomicReference<Option<DhtReplicationChangeValue>> inFlight,
                       AtomicReference<Option<DhtReplicationReportValue>> filed,
                       AtomicReference<Option<DhtReplicationReportValue>> filing,
                       AtomicLong workerOpenChange,
                       AtomicBoolean restored,
                       AtomicLong notBefore,
                       AtomicLong backoffMs,
                       AtomicLong refiles) implements DhtReplicationSettlement {
            @Override
            @Contract
            public void applied(long configVersion) {
                appliedVersion.accumulateAndGet(configVersion, Math::max);
                report();
            }

            @Override
            @Contract
            public void applied(long configVersion, long openChangeVersion) {
                workerOpenChange.set(openChangeVersion);
                applied(configVersion);
            }

            @Override
            @Contract
            public void onConfigCommitted(AetherValue.ClusterConfigValue config) {
                if (!inputs.isLeader().get()) {
                    return;
                }

                ClusterReplication.defaults(Option.some(config)).onSuccess(defaults -> nextChange(committed(),
                                                                                                  config.configVersion(),
                                                                                                  defaults.replicationFactor(),
                                                                                                  defaults.confirmationFactor(),
                                                                                                  inputs.clock()
                                                                                                        .getAsLong()).onPresent(this::propose));
            }

            @Override
            @Contract
            public void onChangeCommitted(Option<DhtReplicationChangeValue> before, DhtReplicationChangeValue after) {
                applyCommitted(inputs.dhtNode(), inputs.declared(), after);
                transition(before, after).onPresent(inputs.events());
                report();
                evaluate();
            }

            @Override
            @Contract
            public void reapply() {
                restored.set(true);
                committed().onPresent(change -> applyCommitted(inputs.dhtNode(), inputs.declared(), change));
                report();
            }

            /// v1882 rounds 4-5: this node's fence becomes KNOWN — and it accepts DHT writes as a replica — only once its
            /// state is restored AND it has applied the consensus log up to the commit point it observed
            /// (`RabiaNode.isPendingCatchUp` false, `inputs.consensusCaughtUp`). A restored snapshot may precede a committed
            /// change still in the log tail; until the tail is applied, its fence could be too old. The signal only sees log
            /// positions this node has been told about, so a tail it has not yet received is not excluded
            /// [unverified: no run shows a confirmed fence older than the committed change] [limit: #1683]. Re-adopts the latest
            /// committed record (or none — the baseline) at that point. Driven by [#report], which runs on every relevant
            /// commit and on the worker-metadata tick, so it needs no trigger of its own. A worker holds no partitions and
            /// receives no writes, so its fence is confirmed as it applies its projection.
            private void confirmFence() {
                if (inputs.dhtNode().acceptsWrites() || !restored.get() || !inputs.consensusCaughtUp().getAsBoolean()) {
                    return;
                }

                committed().onPresent(change -> applyCommitted(inputs.dhtNode(), inputs.declared(), change));
                inputs.dhtNode().confirmReplicationFence();
            }

            @Override
            @Contract
            public void evaluate() {
                if (!inputs.isLeader().get()) {
                    return;
                }

                committed().filter(change -> !change.settled())
                         .flatMap(change -> advance(change,
                                                    liveReports(),
                                                    Set.copyOf(inputs.dhtNode().ring().nodes()),
                                                    inputs.workers().get(),
                                                    inputs.departed(),
                                                    inputs.clock().getAsLong()))
                         .onPresent(this::propose);
            }

            @Override
            @Contract
            public void report() {
                confirmFence();
                if (!inputs.dhtNode().replicationResolved()) {
                    return;
                }

                var desired = new DhtReplicationReportValue(appliedVersion.get(),
                                                            inputs.replica()
                                                            ? inputs.dhtNode().replicationCaughtUpVersion()
                                                            : DHTNode.NO_CHANGE,
                                                            inputs.replica());

                if (alreadyFiled(desired)) {
                    return;
                }

                var key = DhtReplicationReportKey.dhtReplicationReportKey(inputs.self());

                filing.set(Option.some(desired));
                notBefore.accumulateAndGet(inputs.clock().getAsLong() + REFILE_BACKOFF_MIN_MS,
                                           Math::max);
                inputs.submit()
                      .apply(List.of(new KVCommand.Put<>(key, desired)))
                      .withSuccess(_ -> filed.set(Option.some(desired)))
                      .withResult(_ -> filing.set(Option.none()))
                      .withFailure(cause -> LOG.debug("DHT replication report not filed: {}",
                                                      cause.message()));
            }

            /// What this node filed, checked against its own store: the leader drops the report of a member it holds `Dead`, and
            /// a live process that rejoins would otherwise go on believing it filed (#2553). A core reads its KV; a worker reads
            /// its replica, whose filter includes report keys, and only while the replica is ready.
            private boolean alreadyFiled(DhtReplicationReportValue desired) {
                return filing.get()
                             .filter(desired::equals)
                             .isPresent() || filed.get()
                                                  .filter(last -> DhtReplicationSettlement.readsAsFiled(last,
                                                                                                        desired,
                                                                                                        openChangeVersion()))
                                                  .filter(_ -> isHeldOrNotDue())
                                                  .isPresent();
            }

            /// True while the store holds this node's report (which also resets the backoff), or while nothing waits for it, or
            /// while the backoff since the last filing has not elapsed. False decides a re-file: it counts it and arms the next,
            /// doubled, interval.
            private boolean isHeldOrNotDue() {
                if (isHeldInStore()) {
                    backoffMs.set(REFILE_BACKOFF_MIN_MS);

                    return true;
                }

                var now = inputs.clock().getAsLong();

                if (openChangeVersion() <= DHTNode.NO_CHANGE || now < notBefore.get()) {
                    return true;
                }

                var delay = backoffMs.get();

                notBefore.set(now + delay);
                backoffMs.set(Math.min(delay * 2, REFILE_BACKOFF_MAX_MS));
                refiles.incrementAndGet();

                return false;
            }

            private boolean isHeldInStore() {
                var counts = inputs.replica() || inputs.replicaReady().getAsBoolean();

                return ! counts || inputs.kvStore()
                                         .get(DhtReplicationReportKey.dhtReplicationReportKey(inputs.self()))
                                         .isPresent();
            }

            @Override
            public long refileCount() {
                return refiles.get();
            }

            /// The version of the unsettled change this node knows: a core reads the record, a worker also is told by its projection.
            private long openChangeVersion() {
                return Math.max(workerOpenChange.get(),
                                committed().filter(change -> !change.settled())
                                         .map(DhtReplicationChangeValue::version)
                                         .or(DHTNode.NO_CHANGE));
            }

            private Option<DhtReplicationChangeValue> committed() {
                return inputs.kvStore()
                             .getTyped(DhtReplicationChangeKey.dhtReplicationChangeKey(),
                                       DhtReplicationChangeValue.class);
            }

            /// The reports of members the membership view does not hold `Dead`. A departed member's report is dropped here, by the leader, so
            /// it cannot hold a later change for a leader whose membership view never saw that member die.
            private Map<NodeId, DhtReplicationReportValue> liveReports() {
                var reports = reports();
                var departed = reports.keySet().stream().filter(inputs.departed()).toList();

                departed.forEach(this::dropReport);
                departed.forEach(reports::remove);

                return reports;
            }

            private void dropReport(NodeId departed) {
                inputs.submit()
                      .apply(List.of(new KVCommand.Remove<>(DhtReplicationReportKey.dhtReplicationReportKey(departed))))
                      .onFailure(cause -> LOG.debug("DHT replication report of departed {} not removed: {}",
                                                    departed,
                                                    cause.message()));
            }

            private Map<NodeId, DhtReplicationReportValue> reports() {
                var reports = new HashMap<NodeId, DhtReplicationReportValue>();

                inputs.kvStore()
                      .forEach(DhtReplicationReportKey.class,
                               DhtReplicationReportValue.class,
                               (key, value) -> reports.put(key.nodeId(),
                                                           value));

                return reports;
            }

            /// Commit `next` over the record it was computed from. The transaction expects that exact record, so it is
            /// refused when anything committed in between; the next notification recomputes from what did commit.
            private void propose(DhtReplicationChangeValue next) {
                var expected = committed();

                if (inFlight.get().filter(next::equals).isPresent()) {
                    return;
                }

                inputs.kvStore()
                      .getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                      .onPresent(leader -> submitTransaction(leader, expected, next));
            }

            private void submitTransaction(LeaderValue leader,
                                           Option<DhtReplicationChangeValue> expected,
                                           DhtReplicationChangeValue next) {
                var key = DhtReplicationChangeKey.dhtReplicationChangeKey();
                var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                              expected.map(AetherValue.class::cast),
                                                                              Option.some(next));
                var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                          "dht-replication-" + UUID.randomUUID(),
                                                                                          leader,
                                                                                          List.of(),
                                                                                          List.of(mutation));

                inFlight.set(Option.some(next));
                inputs.submit()
                      .apply(List.of(transaction))
                      .withResult(_ -> inFlight.set(Option.none()))
                      .withFailure(cause -> LOG.debug("DHT replication change {} not committed: {}",
                                                      next.version(),
                                                      cause.message()));
            }
        }

        return new running(inputs,
                           new AtomicLong(DHTNode.NO_CHANGE),
                           new AtomicReference<>(Option.none()),
                           new AtomicReference<>(Option.none()),
                           new AtomicReference<>(Option.none()),
                           new AtomicLong(DHTNode.NO_CHANGE),
                           new AtomicBoolean(false),
                           new AtomicLong(0L),
                           new AtomicLong(REFILE_BACKOFF_MIN_MS),
                           new AtomicLong(0L));
    }
}
