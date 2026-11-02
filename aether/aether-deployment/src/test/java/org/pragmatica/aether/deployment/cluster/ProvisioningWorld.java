// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.netty.buffer.ByteBuf;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.deployment.membership.ntt.PresenceSampler;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler.leaderReconciler;
import static org.pragmatica.aether.deployment.membership.ntt.PresenceSampler.presenceSampler;
import static org.pragmatica.aether.environment.ClusterName.maybeClusterName;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The G3 world: the REAL [LeaderReconciler], the REAL [ClusterTopologyManager], the REAL [CapacityControlledLifecycle] and the REAL
/// single-provider lifecycle record, over a real shared [KVStore], a [ModelComputeProvider] and one [VirtualTime]. A leader is a "stack"
/// of those; a leader kill replaces the stack (the ClusterTopologyManagerAutoHealDurabilityTest pattern: several instances, one KV).
///
/// What is modelled, and so what the schedules can and cannot say:
/// - cluster membership is a set of alive core ids, fed to each leader's real [MembershipFsm]; a replacement "boots" [#BOOT_MS] after its
///   server was created and then joins that set (a server that is deleted, never created or told not to boot never joins);
/// - a KILLED leader is a process that is gone: its timers do not fire, its KV writes never commit, its provider calls are never made;
/// - a PARTITIONED leader still believes it leads and can call the provider, but cannot commit to the KV;
/// - the CTM's own real-time timers (the orphan sweep's second read, reap retries) run on the JVM scheduler, not on [VirtualTime]; the
///   world keeps them out of the seeded runs by giving the CTM a one-hour provisioning window, and the cells that need them use
///   [#startSweepingLeader].
final class ProvisioningWorld {
    static final int TARGET = 5;
    static final String CLUSTER = "test-cluster";
    static final long BOOT_MS = 45_000L;
    static final long ELECTION_MS = 3_000L;
    private static final long NTT_PERIOD_MS = 5_000L;
    private static final long REFUSAL_PERIOD_MS = 10_000L;
    private static final long INVENTORY_PERIOD_MS = 300_000L;
    private static final String TOML = """
        config_version = "1.0.0"
        [cluster]
        name = "test-cluster"
        version = "1.0.0"
        [source.default]
        type = "forge"
        [source.default.core]
        count = 5
        """;

    enum KillPoint {
        NONE,
        /// K1: the reservation commit never lands.
        BEFORE_RESERVATION_COMMIT,
        /// K2: the reservation committed, the provider was never asked.
        AFTER_COMMIT_BEFORE_CALL,
        /// K3: the provider applied the create, the reply never reached the leader.
        AFTER_CALL_BEFORE_REPLY,
        /// K4: the reply reached the leader, nothing recorded it as OBSERVED yet.
        AFTER_REPLY_BEFORE_OBSERVED
    }

    /// One observed change of a committed `CapacityReservationKey`.
    record ReservationFact(long seq, long atMs, NodeId node, String phase) {}

    final VirtualTime time = new VirtualTime();
    final ModelComputeProvider provider = new ModelComputeProvider(time);
    final KVStore<AetherKey, AetherValue> store = newStore();
    final Set<NodeId> members = new LinkedHashSet<>();
    final List<ReservationFact> reservationFacts = new ArrayList<>();
    /// Servers that never boot into a member (a node that crash-loops).
    final Set<String> neverBoot = new LinkedHashSet<>();
    /// The first server any create brings into existence never boots into a member.
    boolean firstServerNeverBoots;
    /// Whether a successor inherits the prior leader's gossiped in-flight set (default: the gossip died with the leader).
    boolean gossipSurvives;
    /// PLANT: the CTM provisions through the bare lifecycle, skipping the capacity layer (and so `reserveBound`).
    boolean bypassCapacityLayer;
    /// The configured core count the reconciler is told; a schedule may change it while a replacement is in flight.
    volatile int target = TARGET;
    /// Non-member servers the schedule has explicitly allowed beyond `target - members` (the replacement allowance of I2).
    int allowance;
    /// The cells (K1..K11) this run reached.
    final Set<String> cells = new LinkedHashSet<>();
    /// I2 violations sampled each second of virtual time (first few only).
    final List<String> i2Violations = new ArrayList<>();
    LeaderStack leader;
    private final List<LeaderStack> history = new ArrayList<>();
    private final Map<NodeId, String> lastPhase = new HashMap<>();
    private final AtomicLong term = new AtomicLong(1L);
    final Map<String, Long> bootAt = new LinkedHashMap<>();
    private KillPoint killPoint = KillPoint.NONE;
    private boolean dropNextCommitAck;
    private long lastNtt;
    private long lastRefusal;
    private long lastInventory;
    private final boolean realTimeSweeps;

    ProvisioningWorld() {
        this(false);
    }

    ProvisioningWorld(boolean realTimeSweeps) {
        this.realTimeSweeps = realTimeSweeps;
        provider.afterReply = this::afterCreateReply;
        provider.beforeReply = this::beforeCreateReply;
        provider.afterApply = this::onCreateApplied;
        provider.onFate = fate -> cells.add(fate == ModelComputeProvider.Fate.APPLIED_REPLY_LOST ? "K5" : fate == ModelComputeProvider.Fate.NOT_APPLIED_REPLY_LOST ? "K6" : "-");
        provider.deleteAnnotation = this::deleteAnnotation;
        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(AetherKey.ClusterConfigKey.CURRENT, config()))));
    }

    static NodeId core(int ordinal) {
        return new NodeId("aether-" + CLUSTER + "-node-" + ordinal);
    }

    private static AetherValue config() {
        return new AetherValue.ClusterConfigValue(Option.some(TOML), CLUSTER, "1.0.0", List.of(), 3, 5, "forge", 1, 0);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static KVStore<AetherKey, AetherValue> newStore() {
        return new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });
    }

    // ----------------------------------------------------------------------------------------------- setup and drive

    /// The first formation: `alive` cores are members (the others are dead), the first of them leads, and the committed ledger counts
    /// them as the fleet.
    void start(int alive) {
        for (var i = 1; i <= alive; i++) {
            members.add(core(i));
        }

        commitLedger(alive);
        electFrom(core(1));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void commitLedger(int allocated) {
        var key = AetherKey.CapacityLedgerKey.INSTANCE;

        store.process(store.createBatch(List.of((KVCommand) new KVCommand.Put<>(key, new AetherValue.CapacityLedgerValue(allocated, 1, true)))));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private LeaderStack electFrom(NodeId self) {
        var value = new LeaderValue(self, term.incrementAndGet());

        store.process(store.createBatch(List.of((KVCommand) new KVCommand.Put<>(LeaderKey.INSTANCE, value))));
        var retained = leader == null || !gossipSurvives ? Set.<NodeId> of() : leader.reconciler.inFlightProvisioningKeys();
        var stack = new LeaderStack(self, term.get(), retained);

        leader = stack;
        history.add(stack);
        stack.activate();

        return stack;
    }

    /// Advances virtual time by `millis` in one-second steps, driving what the production hosts drive: the NTT-fire trigger, the refusal
    /// accounting poll, the inventory sweep and the boot of created servers.
    void run(long millis) {
        for (var elapsed = 0L; elapsed < millis; elapsed += 1_000L) {
            time.advance(1_000L);
            bootDueServers();
            drivePeriodic();
            sampleOverProvisioning();
        }
    }

    /// I2, sampled every second: the cluster-labelled servers that are not members never exceed the deficit plus the explicit allowance.
    private void sampleOverProvisioning() {
        var excess = nonMemberServers().size() - Math.max(0, target - members.size()) - allowance;

        if (excess > 0 && i2Violations.size() < 3) {
            i2Violations.add("t=" + (time.nowMs() - 1_000_000L) + "ms: " + nonMemberServers().size() + " non-member servers, deficit "
                             + Math.max(0, target - members.size()) + ", allowance " + allowance);
        }
    }

    private void drivePeriodic() {
        var stack = leader;

        if (stack == null || !stack.alive) {
            return;
        }

        var now = time.nowMs();

        if (now - lastNtt >= NTT_PERIOD_MS) {
            lastNtt = now;
            stack.reconciler.onTopologyUnhealthy();
        }

        if (now - lastRefusal >= REFUSAL_PERIOD_MS) {
            lastRefusal = now;
            stack.lifecycle.reconcileRefusals();
        }

        if (now - lastInventory >= INVENTORY_PERIOD_MS) {
            lastInventory = now;
            stack.lifecycle.reconcileInventory();
        }
    }

    private void bootDueServers() {
        for (var server : provider.servers()) {
            var due = bootAt.get(server.id().value());

            if (due != null && due <= time.nowMs() && !neverBoot.contains(server.name())) {
                bootAt.remove(server.id().value());
                join(new NodeId(server.name()));
            }
        }
    }

    private void onCreateApplied(String name, String instance) {
        bootAt.put(instance, time.nowMs() + BOOT_MS);

        if (firstServerNeverBoots) {
            firstServerNeverBoots = false;
            neverBoot.add(name);
        }
        provider.servers().stream().filter(server -> server.id().value().equals(instance)).findFirst()
                .filter(server -> server.visibleAtMs() - server.createdAtMs() > SourceProfile.DEFAULT_REPLACEMENT_CEILING.millis() + 3 * INVENTORY_PERIOD_MS)
                .ifPresent(_ -> cells.add("K11"));
    }

    final List<String> joinLog = new ArrayList<>();

    void join(NodeId node) {
        joinLog.add((time.nowMs() - 1_000_000L) / 1000 + "s " + node.id().replace("aether-test-cluster-node-", "") + " new=" + !members.contains(node));
        if (members.add(node)) {
            history.stream().filter(stack -> stack.alive).forEach(stack -> stack.admit(node));
        }
    }

    // ------------------------------------------------------------------------------------------------------- faults

    /// Arms a kill of the current leader at the next time the schedule reaches `point` (one-shot).
    void killLeaderAt(KillPoint point) {
        killPoint = point;
    }

    /// The current leader's process is gone now; a successor is elected [#ELECTION_MS] later.
    void killLeader() {
        killLeader(null);
    }

    /// When set, the armed fault point partitions the leader for this long instead of killing it.
    long partitionInsteadOfKillMs;

    private void killLeader(String cell) {
        if (partitionInsteadOfKillMs > 0 && cell != null) {
            cells.add(cell);
            partitionLeader(partitionInsteadOfKillMs);

            return;
        }

        Option.option(cell).onPresent(cells::add);

        var victim = leader;

        victim.die();
        members.remove(victim.self);
        time.after(ELECTION_MS, () -> electSuccessor(victim.self));
    }

    /// The current leader loses its quorum but not its mind: it cannot commit, still believes it leads for `believesMs`, and can still call
    /// the provider. The majority elects a successor [#ELECTION_MS] later.
    void partitionLeader(long believesMs) {
        cells.add("K7");

        var victim = leader;

        victim.partitioned = true;
        time.after(ELECTION_MS, () -> electSuccessor(victim.self));
        time.after(believesMs, victim::demote);
    }

    private void electSuccessor(NodeId deposed) {
        if (provider.outage) {
            cells.add("K8");
        }

        members.stream().filter(node -> !node.equals(deposed)).findFirst().ifPresent(this::electFrom);
    }

    /// PLANT: a create the capacity layer never saw (a recovery that re-issues a create for `name`, or any caller that bypasses the layer).
    void plantCreate(String name) {
        var context = org.pragmatica.aether.environment.ProvisionContext.forBootstrap(ClusterName.clusterName(CLUSTER).unwrap(), "core", SourceName.DEFAULT, name);
        var spec = org.pragmatica.aether.environment.ProvisionSpec.provisionSpec(org.pragmatica.aether.environment.InstanceType.ON_DEMAND, "cx23", "core", context).unwrap();
        var request = org.pragmatica.aether.environment.ProvisionRequest.resolve(spec, provider.providerDefaults()).unwrap();

        provider.createFrom(request);
    }

    /// PLANT: a delete the gate never saw.
    void plantDelete(String name) {
        provider.servers().stream().filter(server -> server.name().equals(name)).findFirst()
                .ifPresent(server -> provider.viewFor(() -> true).terminate(server.id()));
    }

    void nextCommitAckIsLost() {
        dropNextCommitAck = true;
    }

    private void beforeCreateReply(String name) {
        if (killPoint == KillPoint.AFTER_CALL_BEFORE_REPLY) {
            killPoint = KillPoint.NONE;
            killLeader("K3");
        }
    }

    private void afterCreateReply(String name) {
        if (killPoint == KillPoint.AFTER_REPLY_BEFORE_OBSERVED) {
            killPoint = KillPoint.NONE;
            killLeader("K4");
        }
    }

    // ----------------------------------------------------------------------------------------------------- the stack

    /// One leader's process: membership view, reconciler, CTM, capacity layer, all real.
    final class LeaderStack {
        final NodeId self;
        final long term;
        final MutableHealth health = new MutableHealth();
        final MembershipFsm fsm;
        final PresenceSampler sampler;
        final ClusterTopologyManager ctm;
        final CapacityControlledLifecycle lifecycle;
        final LeaderReconciler reconciler;
        volatile boolean alive = true;
        volatile boolean partitioned;
        volatile boolean believesLeader = true;
        private final AtomicLong incarnation = new AtomicLong(1L);
        private final AtomicReference<LeaderReconciler> reconcilerRef = new AtomicReference<>();

        LeaderStack(NodeId self, long term, Set<NodeId> retainedDispatched) {
            this.self = self;
            this.term = term;
            this.fsm = MembershipFsm.membershipFsm(FsmObserver.noop(), time::nowMs, Long.MAX_VALUE, timeSpan(150).millis());
            this.sampler = presenceSampler(self, health, timeSpan(1).seconds(), 1, 1, time::nanoTime);
            members.forEach(this::admit);
            var inner = NodeLifecycleManager.nodeLifecycleManager(Option.some(provider.viewFor(() -> alive)),
                                                                  Option.some(ClusterName.clusterName(CLUSTER).unwrap()),
                                                                  Option.<Integer> none());
            var model = new ModelLifecycle(inner, SourceName.DEFAULT);

            this.lifecycle = CapacityControlledLifecycle.capacityControlledLifecycle(model,
                                                                                    self,
                                                                                    store,
                                                                                    this::commit,
                                                                                    () -> alive && believesLeader,
                                                                                    () -> Integer.MAX_VALUE,
                                                                                    SourceProfile.DEFAULT_REPLACEMENT_CEILING,
                                                                                    time);
            this.ctm = newCtm(bypassCapacityLayer ? model : lifecycle);
            this.reconciler = leaderReconciler(membershipConfig(),
                                               sampler,
                                               fsm,
                                               () -> target,
                                               () -> term,
                                               ctm,
                                               () -> maybeClusterName(CLUSTER),
                                               time,
                                               this::schedule);
            reconcilerRef.set(reconciler);
            reconciler.setRetainedDispatchedSupplier(() -> retainedDispatched);
            reconciler.setInstalledVotersSupplier(this::installedVoters);
            reconciler.setDurableProvisioningSupplier(this::durableAllocations);
        }

        private ClusterTopologyManager newCtm(NodeLifecycleManager lifecycleManager) {
            var snapshotSource = new GenerationSnapshotSource() {
                @Override
                public Option<MembershipView> currentMembershipView() {
                    return Option.none();
                }

                @Override
                public long observedRabiaTerm() {
                    return 0L;
                }
            };
            var nodes = new ArrayList<NodeInfo>();

            for (var i = 1; i <= TARGET; i++) {
                nodes.add(NodeInfo.nodeInfo(core(i), NodeAddress.nodeAddress("localhost", 7100 + i).unwrap()));
            }

            var config = new TopologyConfig(self, TARGET, timeSpan(60).seconds(), timeSpan(1).seconds(), nodes);
            var observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
            var window = realTimeSweeps ? timeSpan(60).millis() : timeSpan(1).hours();
            var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), window).unwrap();
            var liveness = MembershipLiveness.membershipLiveness(fsm::coreCountedMembers,
                                                                 fsm::broadcastEligibleMembers,
                                                                 members::contains,
                                                                 _ -> false,
                                                                 () -> reconcilerRef.get() == null ? Set.of() : reconcilerRef.get().inFlightProvisioningKeys(),
                                                                 () -> target,
                                                                 _ -> Option.none());
            var manager = ClusterTopologyManagerRecord.clusterTopologyManagerRecord(observer,
                                                                                    lifecycleManager,
                                                                                    autoHeal,
                                                                                    DeploymentMap.deploymentMap(),
                                                                                    snapshotSource,
                                                                                    () -> store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class),
                                                                                    this::commit,
                                                                                    () -> AetherValue.ClusterPhase.NORMAL,
                                                                                    time,
                                                                                    _ -> {},
                                                                                    _ -> {},
                                                                                    () -> store.getTyped(AetherKey.AutoHealStateKey.SINGLETON, AetherValue.AutoHealStateValue.class),
                                                                                    liveness);

            manager.setRetirementRefusal(_ -> Option.none());
            manager.setHierarchyStateWriter(HierarchyStateWriter.hierarchyStateWriter(() -> store.getTyped(LeaderKey.INSTANCE, LeaderValue.class), store::get, this::commit));

            return manager;
        }

        void activate() {
            ctm.activate();
            reconciler.activate();
        }

        /// A member (or a replacement that just booted) becomes visible to this leader's membership view.
        void admit(NodeId node) {
            fsm.onMemberDescriptor(NodeInfo.nodeInfo(node,
                                                      NodeAddress.nodeAddress("core-host", 6000).unwrap(),
                                                      Map.of(NodeInfo.LABEL_ROLE, "core")));
            fsm.onSwimHealthy(node, incarnation.getAndIncrement());

            if (!node.equals(self)) {
                health.markHealthy(node);
            }

            sampler.sample();

            if (alive && ctm != null) {
                ctm.onNodeReady(node);
            }
        }

        private java.util.concurrent.ScheduledFuture<?> schedule(Runnable task, org.pragmatica.lang.io.TimeSpan delay) {
            return time.schedule(() -> {
                if (alive) {
                    task.run();
                }
            }, delay);
        }

        private Set<NodeId> installedVoters() {
            return Set.copyOf(members);
        }

        private Map<NodeId, LeaderReconciler.DurableAllocation> durableAllocations() {
            var installed = installedVoters();

            return lifecycle.allocatedNodes("core")
                            .entrySet()
                            .stream()
                            .filter(entry -> !installed.contains(entry.getKey()))
                            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                  entry -> entry.getValue() == CapacityReservationPhase.OBSERVED
                                                                           ? LeaderReconciler.DurableAllocation.CONFIRMED
                                                                           : LeaderReconciler.DurableAllocation.UNCERTAIN));
        }

        void die() {
            alive = false;
            ctm.deactivate();
        }

        void demote() {
            believesLeader = false;
            partitioned = false;
            reconciler.deactivate();
            ctm.deactivate();
        }

        /// This leader's consensus write path. A dead process never completes; a partitioned one cannot reach a quorum; a lost
        /// acknowledgement commits and then reports failure.
        @SuppressWarnings({"rawtypes", "unchecked"})
        Promise<List<Object>> commit(List<KVCommand<AetherKey>> commands) {
            if (!alive) {
                return Promise.promise();
            }

            if (partitioned) {
                return Causes.cause("no quorum: write not committed").promise();
            }

            var reservation = isReservationCommit(commands);

            if (reservation && killPoint == KillPoint.BEFORE_RESERVATION_COMMIT) {
                killPoint = KillPoint.NONE;
                killLeader("K1");

                return Promise.promise();
            }

            List<Object> out = store.<Object> process(store.createBatch((List) commands));

            recordReservationFacts();

            if (reservation && killPoint == KillPoint.AFTER_COMMIT_BEFORE_CALL) {
                killPoint = KillPoint.NONE;
                killLeader("K2");
            }

            if (dropNextCommitAck) {
                dropNextCommitAck = false;

                return Causes.cause("commit acknowledgement lost").promise();
            }

            return Promise.success(out);
        }
    }

    private static boolean isReservationCommit(List<KVCommand<AetherKey>> commands) {
        return commands.stream()
                       .anyMatch(command -> command instanceof KVCommand.LeaderTransaction<AetherKey, ?> transaction
                                            && transaction.mutations()
                                                          .stream()
                                                          .anyMatch(ProvisioningWorld::dispatchesReservation));
    }

    private static boolean dispatchesReservation(KVCommand.Mutation<AetherKey, ?> mutation) {
        return mutation.key() instanceof AetherKey.CapacityReservationKey
               && mutation.expected().isEmpty()
               && mutation.replacement().filter(value -> value instanceof CapacityReservationValue reservation
                                                         && reservation.phase() == CapacityReservationPhase.DISPATCHED).isPresent();
    }

    private void recordReservationFacts() {
        var seen = new HashMap<NodeId, String>();

        Map<?, ?> snapshot = store.snapshot();

        snapshot.forEach((key, value) -> {
            if (key instanceof AetherKey.CapacityReservationKey reservation && value instanceof CapacityReservationValue state) {
                seen.put(reservation.nodeId(), state.phase().name());
            }
        });
        seen.forEach((node, phase) -> {
            if (!phase.equals(lastPhase.get(node))) {
                reservationFacts.add(new ReservationFact(time.seq(), time.nowMs(), node, phase));
            }
        });
        lastPhase.keySet().stream().filter(node -> !seen.containsKey(node)).toList()
                 .forEach(node -> reservationFacts.add(new ReservationFact(time.seq(), time.nowMs(), node, "REMOVED")));
        lastPhase.clear();
        lastPhase.putAll(seen);
    }

    /// A delete made outside Aether. K9 is the one made of a server whose reservation is still live, after a leader change.
    boolean externalDelete(String name) {
        var phase = reservationPhase(new NodeId(name));

        if (phase != null && phase != CapacityReservationPhase.RELEASED && term.get() > 2) {
            cells.add("K9");
        }

        return provider.externalDelete(name);
    }

    /// The provider's view of a delete a caller is making now: tags the call when the victim is a member or holds a live reservation.
    private String deleteAnnotation(String name) {
        var node = new NodeId(name);
        var phase = reservationPhase(node);
        var live = phase == CapacityReservationPhase.DISPATCHED || phase == CapacityReservationPhase.OBSERVED;

        return members.contains(node) ? ":member" : live ? ":reserved-" + phase : "";
    }

    /// Runs `action` `delayMs` of virtual time from now.
    void after(long delayMs, Runnable action) {
        time.after(delayMs, action);
    }

    void lowerTargetTo(int newTarget) {
        allowance = Math.max(allowance, nonMemberServers().size());
        target = newTarget;
    }

    // ------------------------------------------------------------------------------------------------------ queries

    /// Servers that exist at the provider and carry this cluster's label.
    List<ModelComputeProvider.Server> clusterServers() {
        return provider.servers().stream().filter(server -> CLUSTER.equals(server.labels().get(ModelComputeProvider.CLUSTER_TAG))).toList();
    }

    /// Servers of the cluster whose node is not a member: the fleet the cluster is paying for beyond its membership.
    List<ModelComputeProvider.Server> nonMemberServers() {
        return clusterServers().stream().filter(server -> !members.contains(new NodeId(server.name()))).toList();
    }

    CapacityReservationPhase reservationPhase(NodeId node) {
        return store.getTyped(new AetherKey.CapacityReservationKey(node), CapacityReservationValue.class).map(CapacityReservationValue::phase).fold(() -> null, phase -> phase);
    }

    Map<NodeId, CapacityReservationValue> reservations() {
        var result = new LinkedHashMap<NodeId, CapacityReservationValue>();

        Map<?, ?> snapshot = store.snapshot();

        snapshot.forEach((key, value) -> {
            if (key instanceof AetherKey.CapacityReservationKey reservation && value instanceof CapacityReservationValue state) {
                result.put(reservation.nodeId(), state);
            }
        });

        return result;
    }

    private static final class MutableHealth implements Supplier<HealthSnapshot> {
        private final Map<NodeId, SwimHealth> peers = new LinkedHashMap<>();

        synchronized void markHealthy(NodeId node) {
            peers.put(node, SwimHealth.HEALTHY);
        }

        @Override
        public synchronized HealthSnapshot get() {
            return HealthSnapshot.healthSnapshot(Map.copyOf(peers));
        }
    }
}
