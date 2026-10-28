// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.node.AetherNode.QuicTransportCause;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.GossipKeyDivergenceGuard;
import org.pragmatica.aether.node.GossipKeyRotationHandler;
import org.pragmatica.aether.node.SwimGossipEncryptors;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.worker.metrics.WorkerMetricsAggregator;
import org.pragmatica.cluster.metrics.ClusterSyncMessage;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.RotatingGossipEncryptor;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimHealth;
import org.pragmatica.swim.TransportObservation;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.pragmatica.aether.node.AetherNode.COLD_BOOT_CONVERGENCE_WINDOW_MS;
import static org.pragmatica.aether.node.AetherNode.LOG;
import static org.pragmatica.aether.node.AetherNode.configuredWorker;
import static org.pragmatica.aether.node.AetherNode.runtimeReady;
import static org.pragmatica.aether.node.AetherNode.startSwim;
import static org.pragmatica.aether.node.AetherNode.unreachableHint;


/// The SWIM unit of the node assembly: the gossip encryptor and key-rotation handler, then the SWIM failure detector
/// with its boot tokens, cold-boot window, transport hints and the start trigger the boot chain fires once the transport
/// is ready. Moved out of `AetherNode` without change; the root calls the phases in the order it always ran them.
public sealed interface SwimAssembly {
    /// What `gossipEncryption` reads from the assembly built so far.
    record GossipEncryptionInputs(AetherNodeConfig config) {}

    /// What `gossipEncryption` hands back for the rest of the assembly.
    record GossipEncryptionOutputs(RotatingGossipEncryptor rotatingEncryptor,
                                   GossipKeyRotationHandler gossipKeyRotationHandler) {}

    /// The rotating gossip encryptor and the handler that applies a committed gossip-key rotation to it.
    static GossipEncryptionOutputs gossipEncryption(GossipEncryptionInputs in) {
        var config = in.config();
        var rotatingEncryptor = createGossipEncryptor(config);
        var gossipKeyRotationHandler = GossipKeyRotationHandler.gossipKeyRotationHandler(rotatingEncryptor);

        return new GossipEncryptionOutputs(rotatingEncryptor, gossipKeyRotationHandler);
    }

    /// What `swimDetector` reads from the assembly built so far.
    record SwimDetectorInputs(AetherNodeConfig config,
                              MessageRouter.DelegateRouter delegateRouter,
                              RabiaNode<KVCommand<AetherKey>> clusterNode,
                              Serializer serializer,
                              Deserializer deserializer,
                              Runnable jvmExit,
                              Runnable identityRefusedExit,
                              Runnable gossipKeyDivergedExit,
                              long bootToken,
                              ClusterSyncCollector metricsCollector,
                              PeerObservationStore peerObservationStore,
                              BooleanSupplier isLeaderSupplier,
                              Supplier<Epoch> leaderEpochSupplier,
                              Supplier<AetherValue.ClusterPhase> effectivePhaseSupplier,
                              AtomicReference<CoreSwimHealthDetector> swimHealthDetectorHolder,
                              OperatorWarningSink operatorWarningSink,
                              WorkerMetricsAggregator workerMetricsAggregator,
                              NodeId selfId,
                              RotatingGossipEncryptor rotatingEncryptor,
                              ArrayList<MessageRouter.Entry<?>> allEntries) {}

    /// What `swimDetector` hands back for the rest of the assembly.
    record SwimDetectorOutputs(AtomicLong swimBootAtMs,
                               BooleanSupplier swimIsBootingSupplier,
                               CoreSwimHealthDetector swimHealthDetector,
                               Consumer<Promise<Unit>> startSwimTrigger) {}

    /// SWIM configuration, the cold-boot window, the failure detector, boot tokens, transport hints and the start trigger.
    static SwimDetectorOutputs swimDetector(SwimDetectorInputs in) {
        var config = in.config();
        var delegateRouter = in.delegateRouter();
        var clusterNode = in.clusterNode();
        var serializer = in.serializer();
        var deserializer = in.deserializer();
        var jvmExit = in.jvmExit();
        var identityRefusedExit = in.identityRefusedExit();
        var gossipKeyDivergedExit = in.gossipKeyDivergedExit();
        var bootToken = in.bootToken();
        var metricsCollector = in.metricsCollector();
        var peerObservationStore = in.peerObservationStore();
        var isLeaderSupplier = in.isLeaderSupplier();
        var leaderEpochSupplier = in.leaderEpochSupplier();
        var effectivePhaseSupplier = in.effectivePhaseSupplier();
        var swimHealthDetectorHolder = in.swimHealthDetectorHolder();
        var operatorWarningSink = in.operatorWarningSink();
        var workerMetricsAggregator = in.workerMetricsAggregator();
        var selfId = in.selfId();
        var rotatingEncryptor = in.rotatingEncryptor();
        var allEntries = in.allEntries();
        var swimTimeouts = config.timeouts().swim();
        var swimConfig = SwimConfig.fromTimeouts(swimTimeouts.period(),
                                                 swimTimeouts.probeTimeout(),
                                                 swimTimeouts.suspectTimeout())
                                   .withSwimPortOffset(CoreSwimHealthDetector.SWIM_PORT_OFFSET)
                                   // Arm the cross-cluster ANNOUNCE gate. `fromTimeouts` inherits
                                   // DEFAULT's empty cluster name, and empty means "no gating" — so
                                   // until this call the guard existed, was wired, and rejected
                                   // nothing. The name is also what `announceJoin` stamps on
                                   // outbound ANNOUNCEs below, so both sides of the comparison come
                                   // from here. Absent (in-process harnesses that never pass through
                                   // Main) leaves it inert exactly as before.
                                   .withClusterName(config.clusterName().map(ClusterName::value).or(""));
        // Phase-aware SWIM cold-boot suppression (D.3, 2026-05-11). SWIM suppresses
        // FAULTY-for-never-HEALTHY peers ONLY while the cluster is in `COLD_BOOT`
        // (initial formation, never reached quorum). In `NORMAL` and `RECOVERING`,
        // FAULTY edges always emit — the `RECOVERING` branch is the critical fix
        // for compose-restart: peers were Healthy in the prior NORMAL period and a
        // post-restart kill must produce `FaultyObserved` (drives DECOMMISSIONED
        // write + NODE_LEFT / NODE_FAILED downstream event).
        // E.6 / E.8 (spec §7.2): SWIM phase-suppression gate routes through `ClusterPhaseView`
        // (the single source of truth post-E.8).
        //
        // A6 cold-boot convergence window (2026-06-28): the phase-only gate flips out of COLD_BOOT at
        // FIRST quorum (e.g. 3/5), but on a simultaneous full-cluster restart the remaining nodes' QUIC
        // links may not have formed yet — the transport single-dialer force-dials a straggler only after
        // a 60s grace (QuicClusterNetwork.RECONCILE_BACKOFF_CAP_MS). Without a window SWIM would
        // FAULTY-evict (then authoritatively REMOVE) the not-yet-connected seeds at ~quorum time, wedging
        // the cluster permanently below full membership (terminal-removal is irreversible). OR-in a
        // one-shot window anchored at THIS node's boot so never-HEALTHY SEEDS stay UNKNOWN (the existing,
        // tested COLD_BOOT suppression branch — no tombstone, formation-safe) until the transport has
        // exhausted its dial attempts. Proven-HEALTHY peers are unaffected: every suppression branch
        // short-circuits on everSeenHealthy, so a real death still FAULTYs immediately regardless.
        //
        // #642: "boot" is the instant this node STARTS, not the instant it was assembled. The holder is
        // seeded here so an assembled-but-never-started node behaves exactly as before, and re-stamped
        // by start() (`swimBootAt` record component) so the window always covers the node's first
        // COLD_BOOT_CONVERGENCE_WINDOW_MS of RUNNING life. Anchoring at assembly meant a node started
        // more than that window after construction — routine in a staggered-restart harness — booted
        // with the suppression already expired, i.e. zero cold-boot protection.
        var swimBootAtMs = new AtomicLong(System.currentTimeMillis());
        BooleanSupplier swimIsBootingSupplier = () -> coldBootConvergenceActive(effectivePhaseSupplier.get() == AetherValue.ClusterPhase.COLD_BOOT,
                                                                                swimBootAtMs.get(),
                                                                                System.currentTimeMillis(),
                                                                                COLD_BOOT_CONVERGENCE_WINDOW_MS);
        // Leader-faulty evictor (2026-05-09): bridges SWIM-FAULTY → QUIC disconnect when
        // the FAULTY peer IS the current cluster leader. Breaks the consensus.apply
        // broadcast stall on cloud Container kill-leader (post-Step-3 architecture
        // otherwise depends on consensus to evict, but consensus is what's stuck).
        // Narrow trigger preserves Step 3's removal of the N+1 fan-out cascade for
        // general FAULTY peers. See SwimHealthContext.faultyLeaderEvictor field doc.
        Consumer<NodeId> faultyLeaderEvictor = peer -> clusterNode.network()
                                                                  .disconnect(new NetworkServiceMessage.DisconnectNode(peer));
        // Gate RCA fix (2026-06-11): SWIM transport-connectivity veto — a never-HEALTHY peer past
        // its join grace is FAULTY'd ONLY if it ALSO has no live QUIC connection (co-confirmed
        // ghost). Sourced from the network's live connectedPeers() view, deref'd per call (the
        // network reference is live long before SWIM probing starts, so construction order is
        // safe). One-way evidence: transport may VETO a death; it never reports life.
        Predicate<NodeId> swimTransportConnected = peer -> clusterNode.network()
                                                                      .connectedPeers()
                                                                      .contains(peer);
        var swimHealthDetector = CoreSwimHealthDetector.coreSwimHealthDetector(delegateRouter,
                                                                               config.topology(),
                                                                               serializer,
                                                                               deserializer,
                                                                               leaderEpochSupplier,
                                                                               isLeaderSupplier,
                                                                               peerObservationStore,
                                                                               swimConfig,
                                                                               swimIsBootingSupplier,
                                                                               faultyLeaderEvictor,
                                                                               swimTransportConnected);

        swimHealthDetectorHolder.set(swimHealthDetector);
        // ONE boot-token registry per process, shared by SWIM and the QUIC transport: a process
        // either layer refuses can never be admitted by the other (terminal removal, #1528).
        var bootTokens = BootTokens.bootTokens(bootToken);

        swimHealthDetector.setBootTokens(bootTokens);
        // #1574: SWIM's operator warnings reach THIS node's event log, never another Ember node's.
        swimHealthDetector.setOperatorWarningSink(operatorWarningSink);
        clusterNode.network().setBootTokens(bootTokens);
        clusterNode.network().setOfflineBufferCap(config.timeouts().cluster().offlineBufferCap());
        bootTokens.onSelfRefused(reason -> exitRefusedIdentity(config.self(), reason, identityRefusedExit));
        // Process evidence carries the per-process random boot token (equality only); SWIM keeps its
        // independent refutation counter, seeded from wall-clock time, and also carries the token.
        var bootIncarnation = System.currentTimeMillis();

        metricsCollector.setIncarnationSupplier(() -> bootToken);
        metricsCollector.setMembershipIncarnationSupplier(() -> bootToken);
        workerMetricsAggregator.setIncarnationSupplier(() -> bootToken);
        // RC1 (S01 fix) — wire the SWIM-backed liveness check for owner-broadcast eviction
        // hints. Followers REFUSE to act on the owner's `ClusterSyncPing.evictionHints` for
        // peers SWIM observes as HEALTHY; the owner's hint is a SUGGESTION, not authority.
        // SWIM HEALTHY requires actual probe-ack within `suspectTimeout`, so this gate is
        // robust against owner partial-network errors. See `ClusterSyncCollector.processEvictionHints`.
        metricsCollector.setPeerLocallyAlive(nodeId -> swimHealthDetector.healthOf(nodeId) == SwimHealth.HEALTHY);
        // Option 1 (S01) — feed cluster-sync missed-pong into SWIM as a transport-unreachable HINT
        // instead of a destructive disconnect. The owner-side SWIM-HEALTHY early-skip in
        // `emitPingTimeoutIfExceeded` still suppresses the hint for a peer SWIM already trusts. The hint
        // is PEER_UNRESPONSIVE (#1061): it floors and vetoes with the QUIC link CONNECTED, so its count
        // covers only the current link since the last pong (R-a, reset by the QUIC listener attached
        // below and by every pong), and a pong from the peer retracts it (R-b). Neither reports life:
        // only a SWIM probe-ack ends the suspicion.
        metricsCollector.setUnreachableReporter(pingTimeoutReporter(swimHealthDetector::recordTransportHint));
        metricsCollector.addPongListener(pongResponsiveReporter(selfId, swimHealthDetector::recordTransportHint));
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> swimHealthDetector.onLeaderChanged(change.leaderId())));
        var announceTopology = config.topology();
        var selfNodeInfo = announceTopology.coreNodes()
                                           .stream()
                                           .filter(n -> n.id()
                                                         .equals(announceTopology.self()))
                                           .findFirst()
                                           .orElse(null);
        var swimSeeds = announceTopology.coreNodes()
                                        .stream()
                                        .filter(n -> !n.id()
                                                       .equals(announceTopology.self()))
                                        .map(n -> InetSocketAddress.createUnresolved(n.address().host(),
                                                                                     n.address()
                                                                                      .port() + CoreSwimHealthDetector.SWIM_PORT_OFFSET))
                                        .toList();
        Runnable announceJoinTrigger = selfNodeInfo == null
                                       ? () -> {}
                                       : joinAnnouncement(config,
                                                          swimHealthDetector,
                                                          selfNodeInfo,
                                                          swimConfig.clusterName(),
                                                          bootIncarnation,
                                                          bootToken,
                                                          swimSeeds);
        // SWIM start is deferred to transport-ready (invoked from the boot chain alongside
        // `startClusterAsync()`), NOT gated on quorum — see `startSwim` doc. This trigger
        // closes over the encryptor + announce trigger that are only in scope here. It fails the
        // node's start outcome it is handed when SWIM cannot start (#1308).
        Consumer<Promise<Unit>> startSwimTrigger = startOutcome -> startSwim(swimHealthDetector,
                                                                             clusterNode.network(),
                                                                             rotatingEncryptor,
                                                                             announceJoinTrigger,
                                                                             jvmExit,
                                                                             gossipKeyDivergedExit,
                                                                             System::nanoTime,
                                                                             Option.some(() -> runtimeReady(clusterNode)),
                                                                             GossipKeyDivergenceGuard.JOIN_DEADLINE,
                                                                             startOutcome);

        return new SwimDetectorOutputs(swimBootAtMs, swimIsBootingSupplier, swimHealthDetector, startSwimTrigger);
    }

    /// Terminal removal (#1528): a peer refused this process's identity — its NodeId belongs to a retired or
    /// different process, so this process can never be admitted. Log ERROR naming the NodeId and exit
    /// through the identity-refused exit hook (production: `halt(EXIT_IDENTITY_REFUSED)` = 78, distinct from
    /// a drain's [#EXIT_DRAINED], so orchestration/CTM can tell the two apart; Ember: stop and remove the node). Runs off the transport thread that delivered the
    /// refusal, so stopping the node cannot block on its own event loop.
    private static Unit exitRefusedIdentity(NodeId self, String reason, Runnable identityRefusedExit) {
        LOG.error("FATAL: this node's identity {} was refused by the cluster: {}. This NodeId belongs to a retired"
                 + " process; start with a fresh identity. Exiting.",
                  self.id(),
                  reason);
        Thread.ofVirtual().name("refused-identity-exit").start(identityRefusedExit);

        return Unit.unit();
    }

    /// ClusterSync missed-pong reporter: each report is a `PING_TIMEOUT` (`PEER_UNRESPONSIVE`) SWIM
    /// hint (#1061). Package-private so a test pins the cause this call site reports.
    static Consumer<NodeId> pingTimeoutReporter(Consumer<TransportObservation> swimHints) {
        return nodeId -> swimHints.accept(unreachableHint(QuicTransportCause.PING_TIMEOUT, nodeId));
    }

    /// ClusterSync pong listener (#1061 R-b): a pong retracts that peer's `PEER_UNRESPONSIVE` hint via
    /// `PeerResponsive` — ClusterSync withdrawing its own stale evidence on contrary evidence of the
    /// same kind. SWIM state is untouched; SWIM probe-ack remains the sole ALIVE authority. The
    /// A pong from `self` reports nothing — a defensive guard, not a producer: no production path
    /// delivers one (`QuicClusterNetwork.broadcastPayload` iterates peers, which excludes self, and a
    /// pong is sent to the leader only), and there would be no hint about `self` to retract anyway.
    static Consumer<ClusterSyncMessage.ClusterSyncPong> pongResponsiveReporter(NodeId self,
                                                                               Consumer<TransportObservation> swimHints) {
        return pong -> {
            if (!self.equals(pong.sender())) {
                swimHints.accept(new TransportObservation.PeerResponsive(pong.sender()));
            }
        };
    }

    /// The join announcement SWIM runs at transport-ready. A worker holds its ANNOUNCE to the seeds until its
    /// activation directive is committed ([#handleActivationDirective] releases it): before that it has no
    /// community, and the cores' membership scope admits no worker that is not a governor, so every round
    /// was traffic they dropped (#1840). A core announces at once, as it must to be dialed by survivors.
    static Runnable joinAnnouncement(AetherNodeConfig config,
                                     CoreSwimHealthDetector swimHealthDetector,
                                     NodeInfo self,
                                     String clusterName,
                                     long incarnation,
                                     long bootToken,
                                     List<InetSocketAddress> seeds) {
        return configuredWorker(config)
               ? () -> swimHealthDetector.announceJoinWhenAssigned(self,
                                                                   clusterName,
                                                                   incarnation,
                                                                   bootToken,
                                                                   seeds,
                                                                   config.timeouts().cluster().communityAbsence())
               : () -> swimHealthDetector.announceJoin(self, clusterName, incarnation, bootToken, seeds);
    }

    /// Build the boot-time SWIM gossip encryptor. Delegates to the shared
    /// [SwimGossipEncryptors] factory so the running node and the boot-time self-address
    /// reflection probe ([org.pragmatica.aether.SelfAddressResolver]) encrypt gossip with
    /// byte-identical key material (a divergent encryptor would silently fail to decrypt the
    /// seed's `WhoAmI` reply).
    private static RotatingGossipEncryptor createGossipEncryptor(AetherNodeConfig config) {
        return SwimGossipEncryptors.fromCertificateProvider(config.certificateProvider());
    }

    /// Pure predicate for the SWIM cold-boot suppression gate (A6): cold-boot convergence is still
    /// active iff the cluster phase is COLD_BOOT, OR this node started within the convergence window.
    /// Extracted as a static pure function so the window logic is unit-testable without standing up a
    /// node. See `swimIsBootingSupplier` wiring for the rationale.
    static boolean coldBootConvergenceActive(boolean phaseIsColdBoot, long bootAtMs, long nowMs, long windowMs) {
        return phaseIsColdBoot || (nowMs - bootAtMs) < windowMs;
    }

    record unused() implements SwimAssembly {}
}
