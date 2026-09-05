// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.worker.isolation.CoreAbsenceSnapshot;
import org.pragmatica.aether.api.ManagementApiResponses.AutoHealStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.AutoHealToggleResponse;
import org.pragmatica.aether.api.ManagementApiResponses.CircuitBreakerResetResponse;
import org.pragmatica.aether.api.ManagementApiResponses.CircuitBreakerStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterMembershipResponse;
import org.pragmatica.aether.api.ManagementApiResponses.FsmMemberDetail;
import org.pragmatica.aether.api.ManagementApiResponses.GovernorInfo;
import org.pragmatica.aether.api.ManagementApiResponses.GovernorsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.MembershipNodeDetail;
import org.pragmatica.aether.api.ManagementApiResponses.EpochInfo;
import org.pragmatica.aether.api.ManagementApiResponses.OwnershipEntry;
import org.pragmatica.aether.api.ManagementApiResponses.OwnershipResponse;
import org.pragmatica.aether.api.ManagementApiResponses.TopologyNodeDetail;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.http.routing.Handler;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.api.ManagementApiResponses.ClusterTopologyStatusResponse;


public final class ClusterTopologyRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;

    private ClusterTopologyRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ClusterTopologyRoutes clusterTopologyRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new ClusterTopologyRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        Handler<ClusterTopologyStatusResponse> topologyHandler = _ -> buildTopologyStatus();

        return Stream.of(ManagementRoutes.<ClusterTopologyStatusResponse> route(ManagementRoute.CLUSTER_TOPOLOGY).toJson(topologyHandler),
                         ManagementRoutes.<ClusterMembershipResponse> route(ManagementRoute.CLUSTER_MEMBERSHIP_GET).toJson(_ -> buildMembershipResponse()),
                         ManagementRoutes.<OwnershipResponse> route(ManagementRoute.CLUSTER_OWNERSHIP_GET)
                                         .withPath(PathParameter.aString())
                                         .toResult(this::ownershipFor)
                                         .asJson(),
                         ManagementRoutes.<GovernorsResponse> route(ManagementRoute.CLUSTER_GOVERNORS).toJson(this::buildGovernorsResponse),
                         ManagementRoutes.<CircuitBreakerStatusResponse> route(ManagementRoute.CLUSTER_CIRCUIT_BREAKER_STATUS).toJson(_ -> buildCircuitBreakerStatus()),
                         ManagementRoutes.<CircuitBreakerResetResponse> route(ManagementRoute.CLUSTER_CIRCUIT_BREAKER_RESET).toJson(_ -> resetCircuitBreaker()),
                         ManagementRoutes.<AutoHealStatusResponse> route(ManagementRoute.CLUSTER_AUTO_HEAL_STATUS).toJson(_ -> buildAutoHealStatus()),
                         ManagementRoutes.<AutoHealToggleResponse> route(ManagementRoute.CLUSTER_AUTO_HEAL_ENABLE).toJson(_ -> setAutoHeal(true)),
                         ManagementRoutes.<AutoHealToggleResponse> route(ManagementRoute.CLUSTER_AUTO_HEAL_DISABLE).toJson(_ -> setAutoHeal(false)));
    }

    private Promise<CircuitBreakerStatusResponse> buildCircuitBreakerStatus() {
        return ctmOpt().map(ctm -> {
                                var state = ctm.circuitBreakerState();

                                return new CircuitBreakerStatusResponse(state.consecutiveFailures(),
                                                                        state.trippedAt(),
                                                                        state.nextAllowedMs(),
                                                                        state.tripped());
                            })
                     .async(CTM_UNAVAILABLE);
    }

    private Promise<CircuitBreakerResetResponse> resetCircuitBreaker() {
        return ctmOpt().map(ctm -> {
                                var prior = ctm.resetCircuitBreaker("/api/cluster/topology/circuit-breaker/reset");

                                return new CircuitBreakerResetResponse("reset", prior);
                            })
                     .async(CTM_UNAVAILABLE);
    }

    private Promise<AutoHealStatusResponse> buildAutoHealStatus() {
        return ctmOpt().map(ctm -> new AutoHealStatusResponse(ctm.isAutoHealEnabled()))
                     .async(CTM_UNAVAILABLE);
    }

    private Promise<AutoHealToggleResponse> setAutoHeal(boolean enabled) {
        var reason = "/api/cluster/topology/auto-heal/" + (enabled
                                                           ? "enable"
                                                           : "disable");

        return ctmOpt().async(CTM_UNAVAILABLE)
                       .flatMap(ctm -> ctm.setAutoHealEnabled(enabled, reason)
                                          .map(previousState -> new AutoHealToggleResponse(enabled, previousState)));
    }

    private Option<ClusterTopologyManager> ctmOpt() {
        return nodeSupplier.get()
                           .clusterTopologyManager();
    }

    private static final Cause CTM_UNAVAILABLE = Causes.cause("Cluster topology manager not available on this node (not the leader, or node not yet activated)");

    private GovernorsResponse buildGovernorsResponse() {
        var node = nodeSupplier.get();
        var governors = new ArrayList<GovernorInfo>();

        node.kvStore()
            .forEach(GovernorAnnouncementKey.class,
                     GovernorAnnouncementValue.class,
                     (key, value) -> governors.add(toGovernorInfo(key, value)));

        return new GovernorsResponse(List.copyOf(governors));
    }

    private static GovernorInfo toGovernorInfo(GovernorAnnouncementKey key, GovernorAnnouncementValue value) {
        var memberIds = value.members().stream().map(NodeId::id).toList();

        return new GovernorInfo(value.governorId().id(),
                                key.communityId(),
                                value.memberCount(),
                                memberIds);
    }

    private Promise<ClusterTopologyStatusResponse> buildTopologyStatus() {
        return Promise.success(assembleTopologyStatus(nodeSupplier.get()));
    }

    /// SWIM-under-concurrent-loss observability — assemble THIS node's LOCAL membership view
    /// (per-peer FSM lifecycle state + the local quorum-loss self-drain signal). Served LOCAL
    /// (never forwarded), so each survivor answers from its own `MembershipFsm` +
    /// `QuorumLossSnapshot`. A Leaf: pure assembly off already-computed node state.
    private Promise<ClusterMembershipResponse> buildMembershipResponse() {
        return Promise.success(assembleMembershipResponse(nodeSupplier.get()));
    }

    private static final QuorumLossSnapshot QUORUM_LOSS_UNWIRED = new QuorumLossSnapshot(0, 0, false, false);

    /// #590 — what an unwired core-absence detector reports: never armed, never fenced, no countdown.
    /// `-1` reads as "no measurement", distinct from a genuine zero.
    private static final CoreAbsenceSnapshot CORE_ABSENCE_UNWIRED = new CoreAbsenceSnapshot(false, false, -1L, -1L, 0L);

    private static ClusterMembershipResponse assembleMembershipResponse(ManageableNode node) {
        var fsm = node.membershipFsm();
        var snapshot = node.quorumLossSnapshot().or(QUORUM_LOSS_UNWIRED);
        var members = buildMembershipMembers(fsm);

        return new ClusterMembershipResponse(node.self().id(),
                                             fsm.strictCoreMemberCount(),
                                             fsm.coreCountedMembers().size(),
                                             snapshot.requiredThreshold(),
                                             snapshot.belowThreshold(),
                                             snapshot.armed(),
                                             node.coreAbsenceSnapshot().or(CORE_ABSENCE_UNWIRED),
                                             members);
    }

    /// Per-peer membership detail off the node's authoritative `MembershipFsm`: lifecycle state,
    /// incarnation, descriptor role, strict-core membership, and effective-core membership. Sorted
    /// by node id so the output is stable across calls. Includes DEAD members (FSM retains them for
    /// incarnation-fenced rejoin), so a remote operator sees the full per-node membership picture.
    private static List<MembershipNodeDetail> buildMembershipMembers(MembershipFsm fsm) {
        var incarnations = fsm.memberIncarnations();
        var descriptors = fsm.memberDescriptors();
        var strictCore = fsm.strictCoreMembers();
        var countedCore = fsm.coreCountedMembers();

        return fsm.memberStates()
                  .entrySet()
                  .stream()
                  .map(entry -> toMembershipNodeDetail(entry.getKey(),
                                                       entry.getValue(),
                                                       incarnations,
                                                       descriptors,
                                                       strictCore,
                                                       countedCore))
                  .sorted(Comparator.comparing(MembershipNodeDetail::nodeId))
                  .toList();
    }

    private static MembershipNodeDetail toMembershipNodeDetail(NodeId id,
                                                               String fsmState,
                                                               Map<NodeId, Long> incarnations,
                                                               Map<NodeId, MemberDescriptor> descriptors,
                                                               Set<NodeId> strictCore,
                                                               Set<NodeId> countedCore) {
        var descriptor = descriptors.getOrDefault(id, MemberDescriptor.UNKNOWN);
        // Descriptor role is a blank ("unknown") label on an all-core cluster (no role labels);
        // surface the FSM's effective core/worker classification so the field is always present.
        var role = descriptor.isCore()
                   ? "core"
                   : "worker";

        return new MembershipNodeDetail(id.id(),
                                        fsmState,
                                        incarnations.getOrDefault(id, 0L),
                                        role,
                                        strictCore.contains(id),
                                        countedCore.contains(id));
    }

    /// #345 item 1f committed-ownership read. A Condition (routing by `domain`) that delegates to the
    /// per-domain collector, then wraps the sorted entries. Unknown domain → a clean typed failure
    /// (mapped to 400 by the JSON error path), never a throw. LOCAL: every entry is read from THIS
    /// node's committed KV-Store via `forEach`, so `owner`/`epoch` reflect the fenced owner this node
    /// has applied.
    private Result<OwnershipResponse> ownershipFor(String domain) {
        return assembleOwnershipResponse(nodeSupplier.get(), domain);
    }

    static final String DOMAIN_COMMUNITY = "community";
    static final String DOMAIN_DHT = "dht";
    static final String DOMAIN_STREAM = "stream";

    /// Package-visible assembler (mirrors `assembleMembershipResponse`) so the ownership view is unit
    /// testable off a seeded `KVStore` without the HTTP layer. Routes by `domain`; an unrecognized
    /// domain yields `OwnershipError.UnknownDomain` (typed `Cause`, not an exception). Each entry pairs
    /// the committed owner/epoch (from the KV-Store) with THIS node's LOCAL per-domain epoch high-water
    /// snapshot, so `fenced` marks the deposed-owner window (high-water strictly after committed epoch).
    static Result<OwnershipResponse> assembleOwnershipResponse(ManageableNode node, String domain) {
        var highWater = highWaterSnapshot(node);

        return switch (domain) {
            case DOMAIN_COMMUNITY -> Result.success(new OwnershipResponse(domain, communityOwnership(node, highWater)));
            case DOMAIN_DHT -> Result.success(new OwnershipResponse(domain, dhtOwnership(node, highWater)));
            case DOMAIN_STREAM -> Result.success(new OwnershipResponse(domain, streamOwnership(node, highWater)));
            default -> new OwnershipError.UnknownDomain(domain).result();
        };
    }

    /// This node's LOCAL per-ownership-domain epoch high-water snapshot, or an empty map when the node
    /// exposes no fence table (test proxies) — in which case every entry reports `highWater == epoch`
    /// and `fenced == false` (the committed epoch is the floor).
    private static Map<OwnershipDomain, Epoch> highWaterSnapshot(ManageableNode node) {
        return node.ownershipEpochHighWater()
                   .map(OwnershipEpochHighWater::snapshot)
                   .or(Map.of());
    }

    private static List<OwnershipEntry> communityOwnership(ManageableNode node, Map<OwnershipDomain, Epoch> highWater) {
        var entries = new ArrayList<OwnershipEntry>();

        node.kvStore()
            .forEach(GovernorAnnouncementKey.class,
                     GovernorAnnouncementValue.class,
                     (key, value) -> entries.add(ownershipEntry(key.communityId(),
                                                                value.governorId().id(),
                                                                value.fenceEpoch(),
                                                                highWater.get(OwnershipDomain.community(key.communityId())))));

        return sortedByIdentity(entries);
    }

    private static List<OwnershipEntry> dhtOwnership(ManageableNode node, Map<OwnershipDomain, Epoch> highWater) {
        var entries = new ArrayList<OwnershipEntry>();

        node.kvStore()
            .forEach(DhtPartitionOwnershipKey.class,
                     DhtPartitionOwnershipValue.class,
                     (key, value) -> entries.add(ownershipEntry(key.partitionId(),
                                                                value.ownerNodeId().id(),
                                                                value.fenceEpoch(),
                                                                highWater.get(OwnershipDomain.dhtPartition(key.partitionId())))));

        return sortedByIdentity(entries);
    }

    private static List<OwnershipEntry> streamOwnership(ManageableNode node, Map<OwnershipDomain, Epoch> highWater) {
        var entries = new ArrayList<OwnershipEntry>();

        node.kvStore()
            .forEach(StreamPartitionOwnershipKey.class,
                     StreamPartitionOwnershipValue.class,
                     (key, value) -> entries.add(ownershipEntry(key.stream() + ":" + key.partition(),
                                                                value.owner().id(),
                                                                value.fenceEpoch(),
                                                                highWater.get(OwnershipDomain.streamPartition(key.stream(),
                                                                                                              key.partition())))));

        return sortedByIdentity(entries);
    }

    /// Builds one ownership row. `observedHighWater` is the nullable per-domain high-water snapshot
    /// value (absent when the node has no fence table or has never observed the arc): it floors to the
    /// committed `epoch` so `highWater` is never behind `epoch`, and `fenced` is `true` only when the
    /// observed high-water is strictly after the committed epoch (the deposed-owner window).
    private static OwnershipEntry ownershipEntry(String identity, String owner, Epoch epoch, Epoch observedHighWater) {
        var highWater = Option.option(observedHighWater).or(epoch);

        return new OwnershipEntry(identity,
                                  owner,
                                  epochInfo(epoch),
                                  epochInfo(highWater),
                                  highWater.isStrictlyAfter(epoch));
    }

    private static List<OwnershipEntry> sortedByIdentity(List<OwnershipEntry> entries) {
        return entries.stream()
                      .sorted(Comparator.comparing(OwnershipEntry::identity))
                      .toList();
    }

    private static EpochInfo epochInfo(Epoch epoch) {
        return new EpochInfo(epoch.rabiaTerm(), epoch.localCounter());
    }

    /// Typed failure for an unrecognized `domain` path segment — surfaced as a clean bad-request by the
    /// JSON error path instead of a thrown exception.
    sealed interface OwnershipError extends Cause {
        record UnknownDomain(String domain) implements OwnershipError {
            @Override
            public String message() {
                return "Unknown ownership domain '" + domain
                     + "' (expected one of: " + DOMAIN_COMMUNITY
                     + ", " + DOMAIN_DHT
                     + ", " + DOMAIN_STREAM
                     + ")";
            }
        }
    }

    private static ClusterTopologyStatusResponse assembleTopologyStatus(ManageableNode node) {
        var topologyConfig = node.topologyConfig();
        var topologyManager = node.topologyManager();
        var connectedPeers = node.connectedPeerIds();
        var allNodeIds = topologyManager.topology();
        var membershipView = node.membershipView();
        var assignedRoles = assignedRoles(node);
        var coreNodeIds = allNodeIds.stream()
                                    .filter(id -> !topologyManager.isPassive(id))
                                    .filter(id -> isDiscovered(topologyManager, id))
                                    .filter(id -> isLiveLifecycle(membershipView, id))
                                    .map(NodeId::id)
                                    .toList();
        // §6 D1: slot-derived headcount, capped at clusterSize. Cold-start (no slots yet) →
        // SWIM-derived count. The fallback is the FSM's CORE-SCOPED count (Wave 2 call-site
        // audit — the field is named coreCount, so a worker must not inflate it).
        var viewCount = slotDerivedCoreCount(node, membershipView);
        var coreCount = viewCount > 0
                        ? viewCount
                        : node.membershipFsm().coreCountedMembers().size();
        var epoch = Option.some(node.metricsCollector().observedEpoch().toString());
        var workerCount = Math.max(0, connectedPeers.size() - coreCount);
        var nodeDetails = allNodeIds.stream()
                                    .filter(id -> isLiveLifecycle(membershipView, id))
                                    .map(id -> buildNodeDetail(topologyManager,
                                                               id,
                                                               connectedPeers.contains(id),
                                                               assignedRoles))
                                    .toList();

        return new ClusterTopologyStatusResponse(coreCount,
                                                 topologyConfig.coreMax(),
                                                 topologyConfig.coreMin(),
                                                 workerCount,
                                                 topologyConfig.clusterSize(),
                                                 coreNodeIds,
                                                 connectedPeers.size(),
                                                 nodeDetails,
                                                 epoch,
                                                 topologyMode(topologyManager),
                                                 buildFsmMembers(node.membershipFsm(), assignedRoles));
    }

    /// #259: per-node CDM-assigned role from the KV-Store `ActivationDirective` — the authority
    /// that demotes a self-asserted core to worker. Surfaced alongside the descriptor role so a
    /// worker-demoted node is visible as such (it previously read as `core`, hiding the demotion).
    static Map<NodeId, String> assignedRoles(ManageableNode node) {
        var roles = new HashMap<NodeId, String>();

        node.kvStore()
            .forEach(ActivationDirectiveKey.class,
                     ActivationDirectiveValue.class,
                     (key, value) -> roles.put(key.nodeId(),
                                               value.role()));

        return Map.copyOf(roles);
    }

    private static final String UNASSIGNED_ROLE = "UNASSIGNED";

    /// Wave-1 item 6 (cluster-topology-overhaul spec): per-member FSM truth — lifecycle state,
    /// incarnation high-water mark, descriptor role/source — read straight off the node's
    /// authoritative `MembershipFsm` snapshots (`memberStates` / `memberIncarnations` /
    /// `memberDescriptors`). Includes DEAD members (retained for incarnation-fenced rejoin),
    /// so a remote run sees the full membership picture without `docker logs`.
    private static List<FsmMemberDetail> buildFsmMembers(MembershipFsm membershipFsm,
                                                         Map<NodeId, String> assignedRoles) {
        var incarnations = membershipFsm.memberIncarnations();
        var descriptors = membershipFsm.memberDescriptors();

        return membershipFsm.memberStates()
                            .entrySet()
                            .stream()
                            .map(entry -> toFsmMemberDetail(entry.getKey(),
                                                            entry.getValue(),
                                                            incarnations,
                                                            descriptors,
                                                            assignedRoles))
                            .toList();
    }

    private static FsmMemberDetail toFsmMemberDetail(NodeId id,
                                                     String fsmState,
                                                     Map<NodeId, Long> incarnations,
                                                     Map<NodeId, MemberDescriptor> descriptors,
                                                     Map<NodeId, String> assignedRoles) {
        var descriptor = descriptors.getOrDefault(id, MemberDescriptor.UNKNOWN);

        return new FsmMemberDetail(id.id(),
                                   fsmState,
                                   incarnations.getOrDefault(id, 0L),
                                   descriptor.role(),
                                   assignedRoles.getOrDefault(id, UNASSIGNED_ROLE),
                                   descriptor.source());
    }

    /// §6 D1 slot-derived headcount. Counts provisioning slots whose occupant is
    /// present. Because the cluster owns exactly `clusterSize` slots and each slot has at
    /// most one occupant, the result is capped at `clusterSize` by construction — a stale
    /// corpse and its fresh replacement that briefly share a slot's identity contribute at
    /// most one (the slot is read once; only its current `assignedNodeId` is classified).
    /// `MembershipView` supplies the per-occupant lifecycle+health classification input.
    ///
    /// **Cold-start fallback.** Before CTM seeds the durable slot map (very-early bootstrap,
    /// self-only formation), there are no slots in KV. Falls back to the FSM's core-scoped
    /// observed-member count (mirrors `StatusRoutes.fallbackQuorumStatus`) so self-bootstrap
    /// still converges. #583: this previously fell back to the SWIM-derived `reachableOnDutyCount`
    /// (`presentMembers().size()`), which counted every present member regardless of role — a
    /// worker present before slots exist would inflate this "core" count. That role-blindness also
    /// made the caller's `viewCount > 0 ? viewCount : coreCountedMembers()` backstop in
    /// `assembleTopologyStatus` unreachable in practice, since self is always present and this
    /// method's fallback therefore never returned 0. Once slots exist, the slot map is
    /// authoritative.
    static int slotDerivedCoreCount(ManageableNode node, MembershipView view) {
        var occupants = new ArrayList<NodeId>();

        node.kvStore()
            .forEach(ProvisioningSlotKey.class,
                     ProvisioningSlotValue.class,
                     (_, value) -> value.assignedNodeId()
                                        .onPresent(occupants::add));
        if (occupants.isEmpty()) {
            return node.membershipFsm()
                       .coreObservedMembers(node.self())
                       .size();
        }

        int count = 0;

        for (var occupant : occupants) {
            if (isHealthyOccupant(view, occupant)) {
                count++;
            }
        }

        return count;
    }

    /// Per-slot occupancy classification (§5.1 HEALTHY): the slot's occupant counts iff it is
    /// present in the membership view (MembershipView input).
    private static boolean isHealthyOccupant(MembershipView view, NodeId occupant) {
        return view.isPresent(occupant);
    }

    private static String topologyMode(TopologyManager tm) {
        return (tm instanceof TopologyObserver observer)
               ? observer.topologyMode()
                         .name()
               : TopologyObserver.TopologyMode.NORMAL.name();
    }

    /// Discovery, NOT health (#558). This read `state.health() == NodeHealth.HEALTHY`, which was
    /// constant-true — nothing ever drove a node out of HEALTHY — so the filter was an identity
    /// function and the name asserted a check that never happened. Removing the dead vocabulary makes
    /// that explicit; behaviour is unchanged.
    ///
    /// The real liveness filtering at the call site is `isLiveLifecycle`, which reads the membership
    /// view. This predicate only answers "does the observer know this id at all".
    private static boolean isDiscovered(TopologyManager tm, NodeId id) {
        return tm.getState(id)
                 .isPresent();
    }

    /// True when the peer is present in the membership view and keeps a place in the operational
    /// topology. Membership-v2 finale: presence IS being on duty — present peers remain valid
    /// `pick_non_leader` targets, valid CTM provisioning slots, and valid
    /// `/api/cluster/topology` rows.
    private static boolean isLiveLifecycle(MembershipView membershipView, NodeId id) {
        return membershipView.isPresent(id);
    }

    private static TopologyNodeDetail buildNodeDetail(TopologyManager tm,
                                                      NodeId nodeId,
                                                      boolean connected,
                                                      Map<NodeId, String> assignedRoles) {
        var info = tm.get(nodeId);
        var state = tm.getState(nodeId);
        var role = info.flatMap(i -> Option.option(i.labels().get(NodeInfo.LABEL_ROLE))).or("UNKNOWN");
        var assignedRole = assignedRoles.getOrDefault(nodeId, UNASSIGNED_ROLE);
        // #558 — this reported `NodeState.health().name()`, i.e. literally "HEALTHY" for every node the
        // observer had ever discovered, dead ones included: nothing ever drove a node out of HEALTHY.
        // An operator-facing field asserting health it never checked is the same defect as the counts,
        // with a wider blast radius, so the values are now what is actually known.
        //   CONNECTED  — a live transport link is observed right now
        //   DISCOVERED — the observer knows this id, but there is no live link
        //   UNKNOWN    — not in the observer's map at all
        var health = state.isPresent()
                     ? (connected
                        ? "CONNECTED"
                        : "DISCOVERED")
                     : "UNKNOWN";
        var hostname = info.flatMap(i -> Option.option(i.labels().get(NodeInfo.LABEL_HOSTNAME))).or("");
        var zone = info.flatMap(i -> Option.option(i.labels().get(NodeInfo.LABEL_ZONE))).or("");
        var address = info.map(i -> i.address()
                                     .asString()).or("");

        return new TopologyNodeDetail(nodeId.id(), role, assignedRole, health, hostname, zone, address);
    }
}
