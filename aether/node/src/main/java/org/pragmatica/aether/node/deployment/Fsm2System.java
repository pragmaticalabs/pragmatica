// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.AbsenceBounds;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterFsm;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.cluster.fsm2.Seams;
import org.pragmatica.aether.deployment.cluster.fsm2.Settings;
import org.pragmatica.aether.deployment.cluster.fsm2.Timing;
import org.pragmatica.aether.deployment.node.machine.NodeFsm;
import org.pragmatica.aether.deployment.node.machine.NodeFsmConfig;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.deployment.node.machine.NodeFsmTimings;
import org.pragmatica.aether.deployment.wiring.BacklogSampler;
import org.pragmatica.aether.deployment.wiring.ClusterFsmInterpreter;
import org.pragmatica.aether.deployment.wiring.FsmTimers;
import org.pragmatica.aether.deployment.wiring.KvSnapshotter;
import org.pragmatica.aether.deployment.wiring.LocalOpExecutor;
import org.pragmatica.aether.deployment.wiring.NodeFsmInterpreter;
import org.pragmatica.aether.deployment.wiring.SliceRuntimeAdapter;
import org.pragmatica.aether.deployment.wiring.Submitter;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.SwimConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The FSM2 engine assembled from the node's real collaborators (phase-3 design 2, 4.2, 5.2): both interpreters over their mailboxes, the
/// `Submitter` on the node's switchable cluster handle, the snapshotter on the node's store, the local-op executor driving the real slice
/// runtime, the translator into the node's router, warning sink and registry, the seam poller and the membership inputs.
///
/// It owns the **inbound routes** the two FSMs consume (B2 the quorum edge, B3 the activation level-heal, B7 the leader toggle, B9 the core
/// membership arms, B12 the drain report, B14 the state restore), each gated by the [EngineMarkerGuard] where it activates something, so that a
/// node whose engine disagrees with the cluster's activates nothing; and the **stop** (design 7): [#drainNode] (2a, 2b: the `SUSPEND`s of the
/// quorum loss run, within a bound), then the cluster interpreter deactivates and closes and the node interpreter closes (2c). The mailbox
/// depths are watched by a [BacklogSampler] (N-5).
///
/// **No worker.** A worker never gets a `Fsm2System` (N-3): the worker paths are unwired in phase 3, and an interpreter handed to a node that
/// has no revision to filter by would apply the wrong replay rule.
public final class Fsm2System {
    private static final Logger log = LoggerFactory.getLogger(Fsm2System.class);
    private static final String NODE_ENTRY = "node-entry";
    private static final String NODE_ENTRY_HEAL = "node-entry-heal";
    private static final String CLUSTER_ACTIVATION = "cluster-activation";
    /// `[guess: the re-announcement interval the fsm2 follow-up used for R-2.14; a worker's announcement cadence is not wired in phase 3]`
    private static final TimeSpan REANNOUNCE_INTERVAL = TimeSpan.timeSpan(30).seconds();

    /// What the system is built from.
    public record Collaborators(AetherNodeConfig config,
                                ProcessIncarnation process,
                                SwimConfig swim,
                                TimeSpan splitTimeout,
                                KVStore<AetherKey, AetherValue> store,
                                Supplier<ClusterNode<KVCommand<AetherKey>>> cluster,
                                SliceRuntimeAdapter.Collaborators adapter,
                                Supplier<Set<Artifact>> loaded,
                                Runnable selfReady,
                                EffectTranslator.Targets translator,
                                SeamPoller.Sources seamSources,
                                AdmissionSeam admissions,
                                EngineMarkerGuard guard,
                                FsmTimers.TimerService clock) {}

    private final NodeFsmInterpreter node;
    private final ClusterFsmInterpreter cluster;
    private final SeamPoller seams;
    private final BacklogSampler backlog;
    private final long stopWaitMs;
    private final MembershipInputs membership;
    private final EffectTranslator translator;
    private final EngineMarkerGuard guard;
    private final LocalOpExecutor localOps;
    private final AtomicLong quorumSequence = new AtomicLong();
    private final NodeId self;
    private final Collaborators collaborators;
    private final SliceRuntimeAdapter adapter;

    private Fsm2System(NodeFsmInterpreter node,
                       ClusterFsmInterpreter cluster,
                       SeamPoller seams,
                       BacklogSampler backlog,
                       long stopWaitMs,
                       MembershipInputs membership,
                       EffectTranslator translator,
                       EngineMarkerGuard guard,
                       LocalOpExecutor localOps,
                       NodeId self,
                       Collaborators collaborators,
                       SliceRuntimeAdapter adapter) {
        this.self = self;
        this.adapter = adapter;
        this.collaborators = collaborators;
        this.node = node;
        this.cluster = cluster;
        this.seams = seams;
        this.backlog = backlog;
        this.stopWaitMs = stopWaitMs;
        this.membership = membership;
        this.translator = translator;
        this.guard = guard;
        this.localOps = localOps;
    }

    // -- derivations (unit-testable) ----------------------------------------------------------------------------------
    /// The node FSM's configuration: the boot identity, and the timings from `[timeouts.deployment]` with the consensus `applyTimeout` as the
    /// submitting path's (design 12 item 5, P3-Q27, D11): a write attempt outlasts the engine's own verdict by a margin, so that verdict always
    /// arrives first (W-35, core arm).
    public static NodeFsmConfig nodeFsmConfig(AetherNodeConfig config, ProcessIncarnation process) {
        return NodeFsmConfig.nodeFsmConfig(config.self(),
                                           process)
                            .withTimings(NodeFsmTimings.nodeFsmTimings(config.timeouts().deployment(),
                                                                       config.protocol().applyTimeout()));
    }

    /// How long a dead core can stay listed in this leader's core view beyond SWIM's own suspicion window (cluster assessment delta 3).
    /// The core view is `MembershipFsm.coreMembers()`, MEMBER + SUSPECT, so the terms are:
    /// - the probe lead: SWIM probes least-recently-probed first, so a dead core is first probed up to `(maxCores - 1)` periods after it died;
    /// - `2 x probeTimeout`: the direct probe, then the indirect ones, before SWIM suspects it;
    /// - one probe period of tick quantisation of the suspicion's expiry (v-2204 F2);
    /// - `splitTimeout`: the membership's eviction backstop keeps a SUSPECT core counted that long;
    /// - `SeamPoller.INTERVAL_MS`: the core view is sampled, not pushed.
    ///
    /// `maxCores` is the largest core set the bound must cover: SWIM's least-recently-probed rotation visits every probable member once per round
    /// `[guess: the core SWIM ring holds cores only]`.
    /// The configured `coreMax`; when it is unset (0, unlimited) the configured cluster size `[guess: an unlimited core set is bounded by
    /// the cluster size it was configured with]`.
    static int maxCores(TopologyConfig topology) {
        return topology.coreMax() > 0
               ? topology.coreMax()
               : topology.clusterSize();
    }

    public static TimeSpan suspicionLag(SwimConfig swim, int maxCores, TimeSpan splitTimeout) {
        return TimeSpan.timeSpan(SeamPoller.INTERVAL_MS)
                       .millis()
                       .plus(swim.period())
                       .plus(TimeSpan.timeSpan(swim.period().nanos() * Math.max(0, maxCores - 1)).nanos())
                       .plus(swim.probeTimeout())
                       .plus(swim.probeTimeout())
                       .plus(splitTimeout);
    }

    /// The cluster FSM's settings: the atomicity and sizing the node is configured with, the absence bounds derived from the SWIM and
    /// membership terms, the reconcile tick from `[timeouts.deployment]`, the process the correlation ids are qualified by, and the lost-write
    /// backstop after the consensus `applyTimeout` (`submissionExpiry = applyTimeout + 5 s`, MQ-7).
    public static Settings clusterSettings(AetherNodeConfig config,
                                           ProcessIncarnation process,
                                           SwimConfig swim,
                                           TimeSpan splitTimeout) {
        var workerSuspect = config.workerConfig()
                                  .map(worker -> worker.swimSettings()
                                                       .suspectTimeout())
                                  .or(swim.suspectTimeout());
        var bounds = AbsenceBounds.derive(swim.suspectTimeout(),
                                          workerSuspect,
                                          swim.lhmMaxScore(),
                                          SwimConfig.MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER,
                                          REANNOUNCE_INTERVAL,
                                          config.timeouts().cluster().communityAbsence(),
                                          suspicionLag(swim,
                                                       maxCores(config.topology()),
                                                       splitTimeout))
                                  .unwrap();
        var sizing = config.deploymentDefaults().communitySizing();

        return Settings.settings(config.self(),
                                 config.atomicity(),
                                 Set.of(),
                                 bounds)
                       .withTiming(Timing.DEFAULT.withTick(config.timeouts()
                                                                 .deployment()
                                                                 .reconciliationInterval()
                                                                 .millis()))
                       .withCommunitySizing(sizing.targetSize(),
                                            sizing.viabilityFloor())
                       .withSubmissionExpiry(config.protocol()
                                                   .applyTimeout()
                                                   .plus(TimeSpan.timeSpan(5).seconds())
                                                   .millis())
                       .withProcess(process);
    }

    // -- assembly ----------------------------------------------------------------------------------------------------
    public static Fsm2System fsm2System(Collaborators c) {
        return fsm2System(c, NodeFsmInterpreter.STOP_WAIT_MS);
    }

    /// As above, with the bound a stop gives each interpreter (design 7, 2b; `[guess: 10 s, settled by E-07]`) set explicitly.
    public static Fsm2System fsm2System(Collaborators c, long stopWaitMs) {
        var translator = EffectTranslator.effectTranslator(c.translator());
        var reader = Submitter.CommittedReader.committedReader(c.store());
        var submitter = Submitter.submitter(c.cluster(), reader);
        var snapshotter = KvSnapshotter.kvSnapshotter(c.store());
        var nodeConfig = nodeFsmConfig(c.config(), c.process());
        var runtime = SliceRuntimeAdapter.sliceRuntimeAdapter(c.adapter());
        var localOps = LocalOpExecutor.localOpExecutor(runtime,
                                                       c.clock(),
                                                       nodeConfig.timings(),
                                                       translator.localOpCounter());
        var node = NodeFsmInterpreter.nodeFsmInterpreter(new NodeFsmInterpreter.Parts(NodeFsm.nodeFsm(nodeConfig,
                                                                                                      System::currentTimeMillis),
                                                                                      submitter,
                                                                                      snapshotter,
                                                                                      c.loaded(),
                                                                                      c.admissions()::tokenOf,
                                                                                      localOps,
                                                                                      c.clock(),
                                                                                      translator.nodeSink(),
                                                                                      c.selfReady(),
                                                                                      translator.stepObserver()),
                                                         stopWaitMs);
        var poller = new SeamPollerHolder();
        var settings = clusterSettings(c.config(), c.process(), c.swim(), c.splitTimeout());
        var fsm = ClusterFsm.clusterFsm(settings, System::currentTimeMillis, System::nanoTime);
        var cluster = ClusterFsmInterpreter.clusterFsmInterpreter(new ClusterFsmInterpreter.Parts(fsm,
                                                                                                  submitter,
                                                                                                  snapshotter,
                                                                                                  poller::sample,
                                                                                                  () -> c.admissions()
                                                                                                         .admissions(c.seamSources()
                                                                                                                      .members()
                                                                                                                      .get()),
                                                                                                  c.clock(),
                                                                                                  translator.clusterSink(),
                                                                                                  translator.stepObserver()),
                                                                  stopWaitMs);
        var seams = SeamPoller.seamPoller(c.seamSources(), pollerSink(cluster), c.clock());

        poller.bind(seams);
        translator.bindMailboxDepth("node", node::mailboxDepth);
        translator.bindMailboxDepth("cluster", cluster::mailboxDepth);
        var backlog = BacklogSampler.backlogSampler(List.of(BacklogSampler.Watched.watched("node", node::mailboxDepth),
                                                            BacklogSampler.Watched.watched("cluster",
                                                                                           cluster::mailboxDepth)),
                                                    translator.backlogAlarm(),
                                                    c.clock());
        var membership = MembershipInputs.membershipInputs(cluster::deliver, c.admissions(), seams);

        return new Fsm2System(node,
                              cluster,
                              seams,
                              backlog,
                              stopWaitMs,
                              membership,
                              translator,
                              c.guard(),
                              localOps,
                              c.config().self(),
                              c,
                              runtime);
    }

    private static SeamPoller.Sink pollerSink(ClusterFsmInterpreter cluster) {
        return new SeamPoller.Sink() {
            @Override
            public boolean seamsRefreshed(Seams seams) {
                return cluster.refreshSeams(seams);
            }

            @Override
            public boolean processKnown(NodeId node, long token) {
                return cluster.processKnown(node, token);
            }
        };
    }

    /// The seam poller exists after the interpreter that reads it at `Rebuild`; this holder closes that cycle.
    private static final class SeamPollerHolder {
        private volatile SeamPoller poller;

        @Contract
        void bind(SeamPoller value) {
            poller = value;
        }

        Seams sample() {
            return poller.sample();
        }
    }

    // -- the parts, for the wiring and for tests -------------------------------------------------------------------------
    public NodeFsmInterpreter nodeInterpreter() {
        return node;
    }

    public ClusterFsmInterpreter clusterInterpreter() {
        return cluster;
    }

    public SeamPoller seamPoller() {
        return seams;
    }

    public MembershipInputs membership() {
        return membership;
    }

    /// The executor of the node FSM's local steps (stopped with the system), for a test that reads that stop closed it.
    public LocalOpExecutor localOps() {
        return localOps;
    }

    /// The adapter the node FSM's local steps run through, as built: its own collaborators are what it received (v-2204 D1).
    public SliceRuntimeAdapter adapter() {
        return adapter;
    }

    /// What the system was built from, so a test can read that the node handed it its real route publisher, invoker and router (v-2204 F4).
    public Collaborators collaborators() {
        return collaborators;
    }

    // -- lifecycle ---------------------------------------------------------------------------------------------------
    /// Starts both consumers, the seam cadence and the backlog sampler.
    @Contract
    public void start() {
        node.start();
        cluster.start();
        seams.start();
        backlog.start();
    }

    /// Design 7, 2a and 2b: the node's `SUSPEND` drain, run between the PASSIVE edge and the router quiesce. The node interpreter resolves a marker
    /// queued behind the quorum loss, so the `SUSPEND`s it queued are known, and then waits for the local-op executor to run them (and anything
    /// a stale chain left to undo). The wait is bounded; on expiry the stop goes on, WARN `deployment-fsm-stop-timeout`: a slice that is still
    /// serving when the node stops is closed with it, which is what a stop without a drain would have done to every slice.
    @Contract
    @TerminalOperation
    public void drainNode() {
        node.drain()
            .await(TimeSpan.timeSpan(stopWaitMs).millis())
            .onFailure(cause -> log.warn("deployment-fsm-stop-timeout node did not drain within {} ms; the stop goes on: {}",
                                         stopWaitMs,
                                         cause.message()));
    }

    /// Stop ordering (design 7, 2c): the backlog sampler and the seam cadence stop, the cluster interpreter deactivates and closes, then the node
    /// interpreter closes; the local-op executor stops with the node interpreter. Each close waits at most its bound and says so if it expires.
    @Contract
    public void stop() {
        backlog.stop();
        seams.stop();
        cluster.close();
        node.close();
        localOps.close();
    }

    /// The first step of the node's stop sequence under FSM2: the leader's FSM goes Dormant before anything it writes through is stopped.
    @Contract
    public void deactivateCluster() {
        cluster.close();
    }

    // -- B2: the quorum edge ----------------------------------------------------------------------------------------
    /// NI-1: a `ClusterStateNotification`. The sequence gate and the demotion drop are the route's (W-04, MT-05); the FSM only ever sees a genuine
    /// edge.
    @Contract
    public void onClusterStateNotification(ClusterStateNotification notification) {
        if (!notification.advanceSequence(quorumSequence)) {
            counted("QuorumEstablished", "dropped_stale_sequence");
        } else {
            quorumEdge(notification);
        }
    }

    private void quorumEdge(ClusterStateNotification notification) {
        switch (notification.state()) {
            case ACTIVE -> guard.whenOpen(NODE_ENTRY, () -> node.requestEntry(false));
            case PASSIVE -> passive(notification);
        }
    }

    private void passive(ClusterStateNotification notification) {
        if (notification.demoted()) {
            log.info("Node {} demoted to observer of a live quorum: keeping slices serving", self);
            counted("QuorumDisappeared", "dropped_demotion");
        } else {
            guard.whenOpen(NODE_ENTRY,
                           () -> {});
            guard.whenOpen(NODE_ENTRY_HEAL,
                           () -> {});
            node.deliver(new NodeFsmInput.QuorumDisappeared());
        }
    }

    private void counted(String input, String result) {
        translator.nodeSink()
                  .accept(new NodeFsmEffect.Metric("aether_deployment_fsm_inputs_total",
                                                   Map.of("fsm", "node", "input", input, "result", result),
                                                   1L));
    }

    // -- B3: the activation level-heal -----------------------------------------------------------------------------------
    /// NI-6: the periodic driver. A node still Dormant under a live consensus re-requests entry; an active one is skipped by the interpreter.
    @Contract
    public void reconcileActivation(BooleanSupplier consensusActive) {
        if (consensusActive.getAsBoolean()) {
            guard.whenOpen(NODE_ENTRY_HEAL, () -> node.requestEntry(true));
        }
    }

    // -- B12: the drain report ----------------------------------------------------------------------------------------
    /// CI-10: a node reported itself DRAINING (a metrics pong). The cluster FSM acts on it only while it is the leader's, Dormant it drops the input.
    @Contract
    public void onNodeDraining(NodeId draining) {
        cluster.deliver(new ClusterInput.NodeDraining(draining));
    }

    // -- B14: the state restore ---------------------------------------------------------------------------------------
    /// The committed snapshot was restored under this node (`onStateRestored`). A restore is bracketed by the genuine quorum edges (PASSIVE first), so
    /// the node FSM needs nothing and its replay burst is dropped by the core rule; only the leader's FSM is judged, and only if it is Active, which
    /// should be impossible (design 4.4, W-25).
    @Contract
    public void onStateRestored() {
        cluster.stateRestored();
    }

    // -- B7: the leader toggle ----------------------------------------------------------------------------------------
    /// CI-1: this node became, or stopped being, the leader.
    @Contract
    public void onLeaderChange(LeaderNotification.LeaderChange change) {
        guard.whenOpen(CLUSTER_ACTIVATION,
                       change.localNodeIsLeader()
                       ? cluster::activate
                       : cluster::deactivate);
    }
}
