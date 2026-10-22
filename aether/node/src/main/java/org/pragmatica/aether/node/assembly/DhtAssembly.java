// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pragmatica.aether.deployment.generation.BootstrapModule;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.dht.KvOwnerEpochSource;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.DhtReplicationSettlement;
import org.pragmatica.aether.node.DhtWriterStaleWatch;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ForwardingClusterNode;
import org.pragmatica.cluster.node.SwitchableClusterNode;
import org.pragmatica.cluster.node.forward.ForwardApplyRequest;
import org.pragmatica.cluster.node.forward.ForwardApplyResponse;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.dht.DHTAntiEntropy;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNetwork;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.DHTRebalancer;
import org.pragmatica.dht.DHTTopologyListener;
import org.pragmatica.dht.DistributedDHTClient;
import org.pragmatica.dht.LoggingResolveFallbackObserver;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.swim.TransportObservation;

import static org.pragmatica.aether.node.AetherNode.LOG;
import static org.pragmatica.aether.node.AetherNode.configuredWorker;
import static org.pragmatica.aether.node.AetherNode.resolveDhtReplication;


/// The DHT unit of the node assembly: the network adapter that reports transport refusals, the DHT clients, the
/// rebalancer, anti-entropy, topology listener, replication settlement and writer stale watch, the committed replication
/// restore, the DHT message routes with the forward-apply routes beside them, and the anti-entropy ticks. Moved out of
/// `AetherNode` without change; the root calls the phases in the order it always ran them.
public sealed interface DhtAssembly {
    /// What `dhtNetwork` reads from the assembly built so far.
    record DhtNetworkInputs(AetherNodeConfig config,
                            RabiaNode<KVCommand<AetherKey>> clusterNode,
                            DHTNode dhtNode,
                            AtomicReference<MembershipFsm> membershipFsmRef) {}

    /// The DHT network adapter, which forwards to the transport and reports its refusals as outcomes.
    static DHTNetwork dhtNetwork(DhtNetworkInputs in) {
        var config = in.config();
        var clusterNode = in.clusterNode();
        var dhtNode = in.dhtNode();
        var membershipFsmRef = in.membershipFsmRef();
        // Concrete adapter (not a lambda) so we can override `sendOutcome` and forward
        // it to the QUIC transport's tracked-write API. The default DHTNetwork impl
        // just calls `send` and reports Sent — but the DHT quorum collectors need the
        // real synchronous refusal signal to fail-fast against unreachable replicas
        // (see aether/docs/specs/dht-resilience-spec.md Layer 1/3).
        DHTNetwork dhtNetwork = new DHTNetwork() {
            @Contract
            @Override
            public void send(NodeId target, org.pragmatica.consensus.ProtocolMessage msg) {
                var _ = clusterNode.network().send(target, msg);
            }

            @Override
            public Promise<org.pragmatica.consensus.net.WriteOutcome> sendOutcome(NodeId target,
                                                                                  org.pragmatica.consensus.ProtocolMessage msg) {
                return clusterNode.network()
                                  .sendOutcome(target, msg);
            }

            @Override
            public Set<NodeId> livePeers() {
                // Membership-FSM unification (Wave D, consumer #3) + scale-down zombie-write fix
                // (2026-06-12): the DHT live-peer set is sourced from the authoritative
                // MembershipFsm.dhtRoutableMembers() (OBSERVED + MEMBER + SUSPECT) plus self. This is
                // the NARROWER projection that, unlike broadcastEligibleMembers(), ALSO excludes
                // DEPARTING: a drained node (commanded to leave on a scale-down) is about to halt, so
                // routing a NEW DHT replica write/read to it produces a silent zombie target — the QUIC
                // send returns Sent, the QuorumCollector never sees a failure, and the op stalls until
                // the per-op timeout (surfacing as a 500 on a write issued shortly after scale-down).
                // Excluding DEPARTING closes that window at drain-COMMAND time (~1s post-scale-down via
                // the leader ping → DrainRequested), before the node halts and far ahead of the ~14s
                // SWIM aging path. This governs only NEW-op target selection; in-flight ops (tracked by
                // correlation id) and the drainer's own InFlightRequestTracker are untouched, so
                // in-flight drain semantics are preserved. An OBSERVED joining replacement and a
                // SUSPECT flapping member are still UP and can serve reads, so they remain in the set;
                // the per-op timeout plus the new total<quorum fast-fail guard are the safety net. Self
                // is always included (it handles local replicas directly). Before the FSM is published
                // (early boot / non-cluster worker paths) this degrades to the prior transport view
                // (connectedPeers + self), preserving back-compat.
                if (configuredWorker(config)) {
                    return dhtNode.ring()
                                  .nodes()
                                  .stream()
                                  .filter(clusterNode.network().connectedPeers()::contains)
                                  .collect(Collectors.toSet());
                }

                var live = Option.option(membershipFsmRef.get())
                                 .map(MembershipFsm::dhtRoutableMembers)
                                 .or(() -> new HashSet<>(clusterNode.network().connectedPeers()));

                live.add(config.self());

                return live;
            }
        };

        return dhtNetwork;
    }

    /// What `dhtClients` reads from the assembly built so far.
    record DhtClientsInputs(AetherNodeConfig config,
                            KVStore<AetherKey, AetherValue> kvStore,
                            DHTNode dhtNode,
                            DHTNetwork dhtNetwork) {}

    /// What `dhtClients` hands back for the rest of the assembly.
    record DhtClientsOutputs(DistributedDHTClient baseDhtClient,
                             DistributedDHTClient dhtClient,
                             AtomicReference<DHTConfig> cacheDhtConfig,
                             DistributedDHTClient cacheDhtClient,
                             Option<DHTClient> dhtClientOption) {}

    /// The distributed DHT client, the client with the resolve fallback observer, the cache client and the optional client handle.
    static DhtClientsOutputs dhtClients(DhtClientsInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var dhtNode = in.dhtNode();
        var dhtNetwork = in.dhtNetwork();
        var baseDhtClient = DistributedDHTClient.distributedDHTClient(dhtNode,
                                                                      dhtNetwork,
                                                                      KvOwnerEpochSource.kvOwnerEpochSource(kvStore,
                                                                                                            BootstrapModule.CORE_PARTITION_ID));
        // An all-miss resolve reads as a bare "absent" everywhere above the client; this observer writes the WARN that
        // says whether the key is absent everywhere or unreachable, for artifact keys only (other keys' absence is normal
        // traffic and logs at DEBUG). The cache client is scoped from the base client, not from this one:
        // a cache miss is normal traffic and must not WARN.
        var dhtClient = baseDhtClient.withResolveFallbackObserver(LoggingResolveFallbackObserver.loggingResolveFallbackObserver(ArtifactStore::isArtifactMetadataKeyHex,
                                                                                                                                LOG::warn,
                                                                                                                                LOG::debug,
                                                                                                                                LOG::info));
        // The cache namespace declares its own replication in the committed `[cache]` section (#1777 track 1),
        // re-read on every operation; `config.cache()` supplies its timeout and retry policy, and its factors
        // until the committed ones are resolved.
        var cacheDhtConfig = new AtomicReference<>(config.cache());
        var cacheDhtClient = baseDhtClient.scoped(cacheDhtConfig::get);
        var dhtClientOption = Option.<DHTClient> some(dhtClient);

        return new DhtClientsOutputs(baseDhtClient, dhtClient, cacheDhtConfig, cacheDhtClient, dhtClientOption);
    }

    /// What `dhtRebalancing` reads from the assembly built so far.
    record DhtRebalancingInputs(DHTNode dhtNode, DHTNetwork dhtNetwork) {}

    /// What `dhtRebalancing` hands back for the rest of the assembly.
    record DhtRebalancingOutputs(DHTRebalancer dhtRebalancer,
                                 AtomicReference<Predicate<NodeId>> departingSendersRef,
                                 DHTAntiEntropy dhtAntiEntropy,
                                 DHTTopologyListener dhtTopologyListener) {}

    /// The rebalancer, the departing-senders holder, anti-entropy and the topology listener.
    static DhtRebalancingOutputs dhtRebalancing(DhtRebalancingInputs in) {
        var dhtNode = in.dhtNode();
        var dhtNetwork = in.dhtNetwork();
        var dhtRebalancer = DHTRebalancer.dhtRebalancer(dhtNode, dhtNetwork);
        // #1818 round 3: departure pushes are accepted only from departing senders. The predicate needs the
        // ClusterSyncCollector, built further down, so it is resolved through this holder (strict until then).
        var departingSendersRef = new AtomicReference<Predicate<NodeId>>(_ -> false);
        var dhtAntiEntropy = DHTAntiEntropy.dhtAntiEntropy(dhtNode,
                                                           dhtNetwork,
                                                           sender -> departingSendersRef.get()
                                                                                        .test(sender));
        var dhtTopologyListener = DHTTopologyListener.dhtTopologyListener(dhtNode, dhtRebalancer, dhtAntiEntropy);

        return new DhtRebalancingOutputs(dhtRebalancer, departingSendersRef, dhtAntiEntropy, dhtTopologyListener);
    }

    /// What `dhtSettlement` reads from the assembly built so far.
    record DhtSettlementInputs(AetherNodeConfig config,
                               MessageRouter.DelegateRouter delegateRouter,
                               KVStore<AetherKey, AetherValue> kvStore,
                               RabiaNode<KVCommand<AetherKey>> clusterNode,
                               DHTNode dhtNode,
                               AtomicReference<MembershipFsm> membershipFsmRef,
                               SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster) {}

    /// What `dhtSettlement` hands back for the rest of the assembly.
    record DhtSettlementOutputs(DhtReplicationSettlement dhtSettlement, DhtWriterStaleWatch dhtWriterStaleWatch) {}

    /// The replication settlement, its replication-caught-up hook and the writer stale watch.
    static DhtSettlementOutputs dhtSettlement(DhtSettlementInputs in) {
        var config = in.config();
        var delegateRouter = in.delegateRouter();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var dhtNode = in.dhtNode();
        var membershipFsmRef = in.membershipFsmRef();
        var switchableCluster = in.switchableCluster();
        // #1777 CTO ruling R1b: a replication change switches the DHT to its new quorums cluster-wide, by a committed fact
        var dhtSettlement = DhtReplicationSettlement.dhtReplicationSettlement(new DhtReplicationSettlement.Inputs(config.self(),
                                                                                                                  !configuredWorker(config),
                                                                                                                  kvStore,
                                                                                                                  dhtNode,
                                                                                                                  config.artifactRepo(),
                                                                                                                  () -> clusterNode.leaderManager()
                                                                                                                                   .isLeader(),
                                                                                                                  switchableCluster::apply,
                                                                                                                  () -> dhtWritingWorkers(Option.option(membershipFsmRef.get())),
                                                                                                                  member -> committedDeparture(Option.option(membershipFsmRef.get()),
                                                                                                                                               member),
                                                                                                                  delegateRouter::route,
                                                                                                                  System::currentTimeMillis,
                                                                                                                  () -> !clusterNode.isPendingCatchUp()
                                                                                                                  // v1882 r5: a replica accepts writes only once consensus has applied its log tail
                                                                                                                 ));

        dhtNode.onReplicationCaughtUp(_ -> dhtSettlement.report());
        // #1777 (owner rule): this node announces its own writes being refused as stale beyond the bound
        var dhtWriterStaleWatch = DhtWriterStaleWatch.dhtWriterStaleWatch(config.self(),
                                                                          dhtNode,
                                                                          delegateRouter::route,
                                                                          System::currentTimeMillis);

        return new DhtSettlementOutputs(dhtSettlement, dhtWriterStaleWatch);
    }

    /// What `dhtReplicationRestore` reads from the assembly built so far.
    record DhtReplicationRestoreInputs(AetherNodeConfig config,
                                       KVStore<AetherKey, AetherValue> kvStore,
                                       RabiaNode<KVCommand<AetherKey>> clusterNode,
                                       DHTNode dhtNode,
                                       AtomicReference<DHTConfig> cacheDhtConfig,
                                       DhtReplicationSettlement dhtSettlement) {}

    /// Resolves the committed DHT replication once the KV state is restored.
    static Unit dhtReplicationRestore(DhtReplicationRestoreInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var dhtNode = in.dhtNode();
        var cacheDhtConfig = in.cacheDhtConfig();
        var dhtSettlement = in.dhtSettlement();
        // #1777 track 1: restored state is when the committed `[replication]` / `[cache]` factors become readable —
        // or are known to be absent, which means the built-in ones. Later commits re-resolve through the
        // ClusterConfigKey put below.
        clusterNode.onStateRestored(() -> {
            resolveDhtReplication(kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                   AetherValue.ClusterConfigValue.class),
                                  dhtNode,
                                  config,
                                  cacheDhtConfig,
                                  dhtSettlement);
            dhtSettlement.reapply();
        });

        return Unit.unit();
    }

    /// What `dhtRoutes` reads from the assembly built so far.
    record DhtRoutesInputs(AetherNodeConfig config,
                           RabiaNode<KVCommand<AetherKey>> clusterNode,
                           DHTNode dhtNode,
                           DHTNetwork dhtNetwork,
                           DistributedDHTClient dhtClient,
                           DHTRebalancer dhtRebalancer,
                           DHTAntiEntropy dhtAntiEntropy,
                           DHTTopologyListener dhtTopologyListener,
                           ForwardingClusterNode<KVCommand<AetherKey>> forwardingClusterNode,
                           List<MessageRouter.Entry<?>> aetherEntries) {}

    /// The DHT message routes, the membership decision routes into the topology listener and the forward-apply routes.
    static Unit dhtRoutes(DhtRoutesInputs in) {
        var config = in.config();
        var clusterNode = in.clusterNode();
        var dhtNode = in.dhtNode();
        var dhtNetwork = in.dhtNetwork();
        var dhtClient = in.dhtClient();
        var dhtRebalancer = in.dhtRebalancer();
        var dhtAntiEntropy = in.dhtAntiEntropy();
        var dhtTopologyListener = in.dhtTopologyListener();
        var forwardingClusterNode = in.forwardingClusterNode();
        var aetherEntries = in.aetherEntries();

        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.GetRequest.class,
                                                    request -> dhtNode.handleGetRequest(request,
                                                                                        response -> dhtNetwork.send(request.sender(),
                                                                                                                    response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.PutRequest.class,
                                                    request -> dhtNode.handlePutRequest(request,
                                                                                        response -> dhtNetwork.send(request.sender(),
                                                                                                                    response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.RemoveRequest.class,
                                                    request -> dhtNode.handleRemoveRequest(request,
                                                                                           response -> dhtNetwork.send(request.sender(),
                                                                                                                       response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.ExistsRequest.class,
                                                    request -> dhtNode.handleExistsRequest(request,
                                                                                           response -> dhtNetwork.send(request.sender(),
                                                                                                                       response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.GetResponse.class, dhtClient::onGetResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.PutResponse.class, dhtClient::onPutResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.RemoveResponse.class, dhtClient::onRemoveResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.ExistsResponse.class, dhtClient::onExistsResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.DigestRequest.class,
                                                    request -> dhtNode.handleDigestRequest(request,
                                                                                           response -> dhtNetwork.send(request.sender(),
                                                                                                                       response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.DigestResponse.class, dhtAntiEntropy::onDigestResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationDataRequest.class,
                                                    request -> dhtNode.handleMigrationDataRequest(request,
                                                                                                  response -> dhtNetwork.send(request.sender(),
                                                                                                                              response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationDataResponse.class,
                                                    dhtAntiEntropy::onMigrationDataResponse));
        routeDhtRepairPlane(aetherEntries, dhtNode, dhtNetwork, dhtAntiEntropy);
        // #427: a graceful-departure push's ack resolves the departing node's pending push, so its
        // drain can confirm the chunk reached a surviving replica before halting.
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationDataAck.class, dhtRebalancer::onMigrationDataAck));
        aetherEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                                    event -> {
                                                        if (!configuredWorker(config)) {
                                                            dhtTopologyListener.onNodeJoined(event);
                                                        }
                                                    }));
        aetherEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                                    event -> {
                                                        if (!configuredWorker(config)) {
                                                            dhtTopologyListener.onNodeRemoved(event);
                                                        }
                                                    }));
        aetherEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                                    event -> {
                                                        if (!configuredWorker(config)) {
                                                            dhtTopologyListener.onNodeDecommissioned(event);
                                                        }
                                                    }));
        // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
        aetherEntries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown.class,
                                                    dhtTopologyListener::onSelfShutdown));
        // PeerDisconnected → DHT-ring prune is intentionally NOT wired. The naive variant
        // (commit `d3e54717e`, reverted here) pruned aggressively on every transient QUIC
        // drop, which triggered a rebalance storm under sustained write pressure (16-chunk
        // 1MB artifact fan-out): each chunk replication targeting a freshly-pruned peer ran
        // through a re-replication pass that saturated QUIC, which in turn caused
        // `writeIfWritable` watermark drops, which manifest as the 1MB-push hang we tried
        // to fix. Consensus-driven `MembershipDecision.NodeRemoved` (wired via
        // `dhtTopologyListener::onNodeRemoved` above) IS the correct ring-prune trigger:
        // it fires only after the cluster has agreed the node is gone, not on every QUIC
        // flap. Future work: bound transport-level pruning by an observation window
        // (e.g., 30s of unbroken PeerDisconnected with no intervening ConnectionEstablished)
        // before pruning, so genuinely-dead peers eventually leave the ring without the
        // every-flap storm.
        @SuppressWarnings({"unchecked", "rawtypes"})
        MessageRouter.Entry forwardRequestRoute = MessageRouter.Entry.route(ForwardApplyRequest.class,
                                                                            (ForwardApplyRequest request) -> handleForwardApplyRequest(request,
                                                                                                                                       clusterNode));

        aetherEntries.add(forwardRequestRoute);
        @SuppressWarnings({"unchecked", "rawtypes"})
        MessageRouter.Entry forwardResponseRoute = MessageRouter.Entry.route(ForwardApplyResponse.class,
                                                                             forwardingClusterNode::onForwardApplyResponse);

        aetherEntries.add(forwardResponseRoute);

        return Unit.unit();
    }

    /// What `dhtTicks` reads from the assembly built so far.
    record DhtTicksInputs(AetherNodeConfig config,
                          DHTAntiEntropy dhtAntiEntropy,
                          Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// The deferred anti-entropy synchronize and catch-up ticks.
    static Unit dhtTicks(DhtTicksInputs in) {
        var config = in.config();
        var dhtAntiEntropy = in.dhtAntiEntropy();
        var defer = in.defer();
        // #420/#1136: the DHT anti-entropy cycle. `DHTAntiEntropy.start()` had no caller, so the
        // "periodic 30 s repair" never ran in production and `[timeouts.dht] anti_entropy_interval`
        // was parsed by nothing. Armed here, with the node's other periodic work (#644: after
        // formation, cancelled first on stop, never a zombie), on the configured interval; the
        // join-time round in DHTTopologyListener is the fast path, this cycle is its retry.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(dhtAntiEntropy::synchronizeNow,
                                                               config.timeouts().dht().antiEntropyInterval()));
        // #1777 track 2: fills the partitions this node is a replica of but not yet authoritative for —
        // at boot, and after every ring change — within about a tick of their sources answering.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(dhtAntiEntropy::catchUpNow,
                                                               DHTAntiEntropy.CATCH_UP_INTERVAL));

        return Unit.unit();
    }

    /// A6: the repair plane's current protocol — stamp digests and paged pulls — and the freshness-gated verified read
    /// of content-addressed keys, answered with a Get- or ExistsResponse. The legacy DHT routes stay beside these for
    /// peers still on the previous version during a rolling upgrade. Without the verified-read route every remote
    /// verified read waits out its phase deadline before falling back to the quorum read.
    @Contract
    static void routeDhtRepairPlane(List<MessageRouter.Entry<?>> allEntries,
                                    DHTNode dhtNode,
                                    DHTNetwork dhtNetwork,
                                    DHTAntiEntropy dhtAntiEntropy) {
        allEntries.add(MessageRouter.Entry.route(DHTMessage.StampDigestRequest.class,
                                                 request -> dhtNode.handleStampDigestRequest(request,
                                                                                             response -> dhtNetwork.send(request.sender(),
                                                                                                                         response))));
        allEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationPageRequest.class,
                                                 request -> dhtNode.handleMigrationPageRequest(request,
                                                                                               response -> dhtNetwork.send(request.sender(),
                                                                                                                           response))));
        allEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationPageResponse.class,
                                                 dhtAntiEntropy::onMigrationPageResponse));
        allEntries.add(MessageRouter.Entry.route(DHTMessage.VerifiedReadRequest.class,
                                                 request -> dhtNode.handleVerifiedReadRequest(request,
                                                                                              response -> dhtNetwork.send(request.sender(),
                                                                                                                          response))));
    }

    /// #1777 R1b: the workers a replication change waits for — every worker the membership tracks that has not been
    /// marked Dead in the leader's membership view. Any of them may write to the DHT. The view decides only WHEN a change
    /// settles; the replication-change fence on every put keeps a writer it misses safe (#1777 R1c).
    static Set<NodeId> dhtWritingWorkers(Option<MembershipFsm> membershipFsm) {
        return membershipFsm.map(fsm -> fsm.memberDescriptors()
                                           .entrySet()
                                           .stream()
                                           .filter(entry -> "worker".equalsIgnoreCase(entry.getValue().role()))
                                           .map(Map.Entry::getKey)
                                           .filter(fsm::isTrackedAndNotDead)
                                           .collect(Collectors.toSet()))
                            .or(Set.of());
    }

    /// #1777 R1b: whether the leader's membership view holds `member` `Dead`. Not a committed fact: a wrong verdict only
    /// hastens or delays a settle (#1777 R1c).
    static boolean committedDeparture(Option<MembershipFsm> membershipFsm, NodeId member) {
        return membershipFsm.map(fsm -> "Dead".equals(fsm.memberStates().get(member)))
                            .or(false);
    }

    @SuppressWarnings({"unchecked", "rawtypes", "JBCT-RET-01"})
    private static void handleForwardApplyRequest(ForwardApplyRequest request,
                                                  RabiaNode<KVCommand<AetherKey>> clusterNode) {
        clusterNode.apply((List) request.commands())
                   .onSuccess(results -> sendSuccessResponse(clusterNode,
                                                             request,
                                                             (List) results))
                   .onFailure(cause -> sendFailureResponse(clusterNode,
                                                           request,
                                                           (Cause) cause));
    }

    @SuppressWarnings({"rawtypes"})
    private static void sendSuccessResponse(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                            ForwardApplyRequest request,
                                            List<?> results) {
        var response = new ForwardApplyResponse<>(clusterNode.self(), request.correlationId(), results, Option.empty());

        clusterNode.network().send(request.sender(), response);
    }

    @SuppressWarnings({"rawtypes"})
    private static void sendFailureResponse(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                            ForwardApplyRequest request,
                                            Cause cause) {
        var response = new ForwardApplyResponse<>(clusterNode.self(),
                                                  request.correlationId(),
                                                  List.of(),
                                                  Option.some(cause.message()));

        clusterNode.network().send(request.sender(), response);
    }

    record unused() implements DhtAssembly {}
}
