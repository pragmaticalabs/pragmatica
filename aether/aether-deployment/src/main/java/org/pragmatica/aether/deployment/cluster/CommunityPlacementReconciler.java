// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PlacementOperationPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Serial leader-side placement workflow. The durable phase precedes every provider effect;
/// replacement readiness precedes drain, and drain acknowledgement precedes termination.
public interface CommunityPlacementReconciler {
    Promise<Unit> reconcile();
    Promise<Unit> requestRetirement(NodeId node, AetherValue.TopologyEntry expectedTopology);
    Promise<Boolean> onDrainCompleted(NodeId sender, String operationId);

    /// #1543 E2: a worker replacement pairing brings a surge worker into a community on purpose. `protectedNodes` (a live
    /// pairing's original before it retires, and its replacement throughout) are never chosen as the node a reduction removes.
    /// Without it the surge reads as an excess and the placement removes whichever member sorts first, which can be the
    /// replacement itself.
    ///
    /// `surgeNodes` are the pairings' replacements: capacity added on purpose, so they are not counted when deciding whether a
    /// community holds an excess. Without it the shield alone moves the excess onto the first UNSHIELDED member, an innocent one.
    default Unit protectReplacements(Supplier<java.util.Set<NodeId>> protectedNodes,
                                     Supplier<java.util.Set<NodeId>> surgeNodes) {
        return Unit.unit();
    }

    /// The nodes a reduction must not remove now. Read-only, for the boot test that pins the node's wiring.
    default java.util.Set<NodeId> protectedView() {
        return java.util.Set.of();
    }

    /// The pairings' replacements not counted as an excess now. Read-only, for the boot test that pins the node's wiring.
    default java.util.Set<NodeId> surgeView() {
        return java.util.Set.of();
    }

    interface Actuator {
        org.pragmatica.lang.Result<String> sourceBinding(org.pragmatica.aether.environment.SourceName source);

        Promise<Unit> create(CommunityPlacementOperationValue operation);
        Promise<Boolean> retirementSafe(CommunityPlacementOperationValue operation);
        Promise<Unit> drain(CommunityPlacementOperationValue operation);
        Promise<Unit> terminate(CommunityPlacementOperationValue operation);
        Promise<Boolean> previousInstanceExists(CommunityPlacementOperationValue operation);
    }

    static CommunityPlacementReconciler communityPlacementReconciler(NodeId self,
                                                                     KVStore<AetherKey, AetherValue> store,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                                                                     BooleanSupplier activeLeader,
                                                                     Predicate<NodeId> ready,
                                                                     Supplier<java.util.Set<NodeId>> admittedNodes,
                                                                     IntSupplier fleetLimit,
                                                                     Actuator actuator,
                                                                     Consumer<CommunityPlacementOperationValue> escalation,
                                                                     java.util.function.BiConsumer<NodeId, org.pragmatica.lang.Cause> retirementRefusal,
                                                                     org.pragmatica.lang.io.TimeSpan drainTimeout) {
        return communityPlacementReconciler(self,
                                            store,
                                            apply,
                                            activeLeader,
                                            ready,
                                            admittedNodes,
                                            fleetLimit,
                                            actuator,
                                            escalation,
                                            retirementRefusal,
                                            drainTimeout,
                                            System::currentTimeMillis);
    }

    /// As above, with the wall clock injected (#1535): the drain deadline, the readiness timeout and the
    /// availability retry read `clock` (epoch milliseconds), so a test can step them deterministically.
    /// Wall-clock, not monotonic, on purpose: the stamps are written to the replicated KV and compared by
    /// whichever leader reads them next.
    static CommunityPlacementReconciler communityPlacementReconciler(NodeId self,
                                                                     KVStore<AetherKey, AetherValue> store,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                                                                     BooleanSupplier activeLeader,
                                                                     Predicate<NodeId> ready,
                                                                     Supplier<java.util.Set<NodeId>> admittedNodes,
                                                                     IntSupplier fleetLimit,
                                                                     Actuator actuator,
                                                                     Consumer<CommunityPlacementOperationValue> escalation,
                                                                     java.util.function.BiConsumer<NodeId, org.pragmatica.lang.Cause> retirementRefusal,
                                                                     org.pragmatica.lang.io.TimeSpan drainTimeout,
                                                                     java.util.function.LongSupplier clock) {
        return new PlacementReconciler(self,
                                       store,
                                       apply,
                                       activeLeader,
                                       ready,
                                       admittedNodes,
                                       fleetLimit,
                                       actuator,
                                       escalation,
                                       retirementRefusal,
                                       new java.util.concurrent.ConcurrentHashMap<>(),
                                       drainTimeout,
                                       new AtomicBoolean(),
                                       new java.util.concurrent.atomic.AtomicReference<>(java.util.Set::of),
                                       new java.util.concurrent.atomic.AtomicReference<>(java.util.Set::of),
                                       clock,
                                       new java.util.concurrent.ConcurrentHashMap<>());
    }
}

record PlacementReconciler(NodeId self,
                           KVStore<AetherKey, AetherValue> store,
                           Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                           BooleanSupplier activeLeader,
                           Predicate<NodeId> ready,
                           Supplier<java.util.Set<NodeId>> admittedNodes,
                           IntSupplier fleetLimit,
                           CommunityPlacementReconciler.Actuator actuator,
                           Consumer<CommunityPlacementOperationValue> escalation,
                           java.util.function.BiConsumer<NodeId, org.pragmatica.lang.Cause> retirementRefusal,
                           java.util.concurrent.ConcurrentHashMap<NodeId, String> reportedRetirementRefusals,
                           org.pragmatica.lang.io.TimeSpan drainTimeout,
                           AtomicBoolean running,
                           java.util.concurrent.atomic.AtomicReference<Supplier<java.util.Set<NodeId>>> protectedNodes,
                           java.util.concurrent.atomic.AtomicReference<Supplier<java.util.Set<NodeId>>> surgeNodes,
                           java.util.function.LongSupplier clock,
                           java.util.concurrent.ConcurrentHashMap<String, Long> emptySince) implements CommunityPlacementReconciler {
    @Override
    public java.util.Set<NodeId> protectedView() {
        return protectedNodes.get()
                             .get();
    }

    @Override
    public java.util.Set<NodeId> surgeView() {
        return surgeNodes.get()
                         .get();
    }

    @Override
    public Unit protectReplacements(Supplier<java.util.Set<NodeId>> protectedSupplier,
                                    Supplier<java.util.Set<NodeId>> surgeSupplier) {
        protectedNodes.set(protectedSupplier);
        surgeNodes.set(surgeSupplier);

        return Unit.unit();
    }

    @Override
    public Promise<Unit> reconcile() {
        if (!activeLeader.getAsBoolean() || !running.compareAndSet(false, true)) {
            return Promise.unitPromise();
        }

        var snapshot = aetherSnapshot();

        return configuration(snapshot).fold(Promise::unitPromise,
                                            config -> reconcileConfiguration(config, snapshot))
                            .onResultRun(() -> running.set(false));
    }

    private Map<AetherKey, AetherValue> aetherSnapshot() {
        Map<?, ?> raw = store.snapshot();
        var snapshot = new java.util.HashMap<AetherKey, AetherValue>();

        raw.forEach((key, value) -> {
            if (key instanceof AetherKey aetherKey && value instanceof AetherValue aetherValue) {
                snapshot.put(aetherKey, aetherValue);
            }
        });

        return snapshot;
    }

    private Option<ClusterBootstrapConfig> configuration(Map<AetherKey, AetherValue> snapshot) {
        return Option.option(snapshot.get(AetherKey.ClusterConfigKey.CURRENT))
                     .filter(AetherValue.ClusterConfigValue.class::isInstance)
                     .map(AetherValue.ClusterConfigValue.class::cast)
                     .flatMap(AetherValue.ClusterConfigValue::tomlContent)
                     .flatMap(toml -> ClusterBootstrapConfigParser.parse(toml).option());
    }

    @Override
    public Promise<Unit> requestRetirement(NodeId node, AetherValue.TopologyEntry expectedTopology) {
        return requestImplicitRetirement(node, expectedTopology).onFailure(cause -> reportRetirementRefusal(node, cause))
                                        .onSuccess(_ -> reportedRetirementRefusals.remove(node));
    }

    @Contract
    private synchronized void reportRetirementRefusal(NodeId node, org.pragmatica.lang.Cause cause) {
        if (reportedRetirementRefusals.size() >= 1024 && !reportedRetirementRefusals.containsKey(node)) {
            return;
        }

        var previous = Option.option(reportedRetirementRefusals.put(node, cause.message()));

        if (previous.filter(cause.message()::equals).isEmpty()) {
            retirementRefusal.accept(node, cause);
        }
    }

    private Promise<Unit> requestImplicitRetirement(NodeId node, AetherValue.TopologyEntry expectedTopology) {
        var snapshot = aetherSnapshot();
        var config = store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class);
        var directive = store.getTyped(new AetherKey.ActivationDirectiveKey(node),
                                       AetherValue.ActivationDirectiveValue.class);
        var placement = store.getTyped(new AetherKey.NodePlacementKey(node), AetherValue.NodePlacementValue.class);

        if (config.filter(value -> value.desiredTopology()
                                        .contains(expectedTopology)).isEmpty() || configuration(snapshot).filter(value -> value.communities()
                                                                                                                               .isEmpty())
                                                                                               .isEmpty() || directive.filter(value -> value.role()
                                                                                                                                            .equals(AetherValue.ActivationDirectiveValue.WORKER))
                                                                                                                      .isEmpty() || placement.filter(value -> value.sourceName()
                                                                                                                                                                   .equals(expectedTopology.sourceName()))
                                                                                                                                             .isEmpty()) {
            return org.pragmatica.lang.utils.Causes.cause("Implicit retirement requires unchanged capacity intent and committed worker placement")
                                                   .promise();
        }

        var community = directive.unwrap().communityId();
        var existing = operation(community);

        if (existing.filter(CommunityPlacementOperationValue::active).isPresent()) {
            return Promise.unitPromise();
        }

        var observed = placement.unwrap();

        return currentLeader().fold(() -> HierarchyStateWriter.Refusal.CONFLICT.promise(),
                                    leader -> actuator.sourceBinding(org.pragmatica.aether.environment.SourceName.sourceNameOrDefault(observed.sourceName()))
                                                      .async()
                                                      .flatMap(binding -> commitImplicitRetirement(node,
                                                                                                   community,
                                                                                                   observed,
                                                                                                   binding,
                                                                                                   leader,
                                                                                                   existing,
                                                                                                   snapshot)));
    }

    private Promise<Unit> commitImplicitRetirement(NodeId node,
                                                   String community,
                                                   AetherValue.NodePlacementValue placement,
                                                   String binding,
                                                   LeaderValue leader,
                                                   Option<CommunityPlacementOperationValue> existing,
                                                   Map<AetherKey, AetherValue> snapshot) {
        var value = new CommunityPlacementOperationValue(UUID.randomUUID().toString(),
                                                         community,
                                                         node,
                                                         placement.sourceName(),
                                                         placement.observedZone(),
                                                         binding,
                                                         Option.some(node),
                                                         placement.sourceName(),
                                                         PlacementOperationPhase.AWAITING_READY,
                                                         leader,
                                                         clock.getAsLong(),
                                                         clock.getAsLong(),
                                                         "Implicit source capacity reduction");
        var guards = new ArrayList<>(reservationGuardsFromSnapshot(snapshot));
        var directiveKey = new AetherKey.ActivationDirectiveKey(node);
        var placementKey = new AetherKey.NodePlacementKey(node);

        guards.add(new KVCommand.ReadWitness<>(directiveKey,
                                               Option.option(snapshot.get(directiveKey))));
        guards.add(new KVCommand.ReadWitness<>(placementKey,
                                               Option.option(snapshot.get(placementKey))));

        return commit(value, existing, guards).flatMap(accepted -> accepted
                                                                   ? Promise.unitPromise()
                                                                   : HierarchyStateWriter.Refusal.CONFLICT.promise());
    }

    private Promise<Unit> reconcileConfiguration(ClusterBootstrapConfig config, Map<AetherKey, AetherValue> snapshot) {
        var identities = new java.util.TreeSet<>(config.communities().keySet());

        snapshot.values()
                .stream()
                .filter(CommunityPlacementOperationValue.class::isInstance)
                .map(CommunityPlacementOperationValue.class::cast)
                .filter(CommunityPlacementOperationValue::active)
                .map(CommunityPlacementOperationValue::communityId)
                .forEach(identities::add);

        return ReconciliationBatch.reconcile(List.copyOf(identities),
                                             4,
                                             org.pragmatica.lang.io.TimeSpan.timeSpan(30).seconds(),
                                             this::reconcileIdentity,
                                             (identity, cause) -> org.slf4j.LoggerFactory.getLogger(CommunityPlacementReconciler.class)
                                                                                         .warn("Community {} reconciliation deferred: {}",
                                                                                               identity,
                                                                                               cause.message()))
                                  .flatMap(_ -> reconcileDerived(config));
    }

    /// #241: the derived communities (`<source>-w-N`) of a cluster that declares no `[community.*]` follow the committed
    /// `[community_policy]`: their target size is re-stamped to `max_size`, and with growth on an empty one is dissolved after the
    /// grace. Declared communities are never touched here. Needs a committed policy: without one there is nothing to follow.
    private Promise<Unit> reconcileDerived(ClusterBootstrapConfig config) {
        return config.communities()
                     .isEmpty()
               ? config.communityPolicy()
                       .fold(Promise::unitPromise, this::reconcileDerivedCommunities)
               : Promise.unitPromise();
    }

    private Promise<Unit> reconcileDerivedCommunities(org.pragmatica.aether.config.cluster.CommunityPolicy policy) {
        var snapshot = aetherSnapshot();
        var views = derivedViews(snapshot);
        var since = timeEmptiness(views);
        var actions = DerivedCommunityPlan.plan(views, policy, since, clock.getAsLong());

        return currentLeader().fold(Promise::unitPromise, leader -> applyDerivedActions(actions, leader, snapshot));
    }

    private static List<DerivedCommunityPlan.View> derivedViews(Map<AetherKey, AetherValue> snapshot) {
        var members = snapshot.values()
                              .stream()
                              .filter(AetherValue.ActivationDirectiveValue.class::isInstance)
                              .map(AetherValue.ActivationDirectiveValue.class::cast)
                              .filter(directive -> !directive.communityId()
                                                             .isBlank())
                              .collect(Collectors.groupingBy(AetherValue.ActivationDirectiveValue::communityId,
                                                             Collectors.counting()));

        return snapshot.entrySet()
                       .stream()
                       .filter(entry -> entry.getKey() instanceof AetherKey.CommunityKey && entry.getValue() instanceof AetherValue.CommunityValue)
                       .map(entry -> derivedView(((AetherKey.CommunityKey) entry.getKey()).communityId(),
                                                 (AetherValue.CommunityValue) entry.getValue(),
                                                 members))
                       .flatMap(Option::stream)
                       .toList();
    }

    private static Option<DerivedCommunityPlan.View> derivedView(String id,
                                                                 AetherValue.CommunityValue community,
                                                                 Map<String, Long> members) {
        return CommunityGrowthPolicy.derivedIndex(community.sourceName(),
                                                  id)
                                    .map(_ -> DerivedCommunityPlan.View.view(id,
                                                                             community.sourceName(),
                                                                             community.state(),
                                                                             community.targetSize(),
                                                                             members.getOrDefault(id, 0L).intValue()));
    }

    /// Emptiness is timed in this leader's memory: a community first seen empty now starts its grace now, a community seen
    /// with a member is forgotten. A new leader restarts every grace, which delays a dissolve and never hastens one.
    private Map<String, Long> timeEmptiness(List<DerivedCommunityPlan.View> views) {
        var empty = DerivedCommunityPlan.emptyOpen(views);

        emptySince.keySet().retainAll(empty);
        empty.forEach(id -> emptySince.putIfAbsent(id, clock.getAsLong()));

        return Map.copyOf(emptySince);
    }

    private Promise<Unit> applyDerivedActions(List<DerivedCommunityPlan.Action> actions,
                                              LeaderValue leader,
                                              Map<AetherKey, AetherValue> snapshot) {
        return actions.stream()
                      .reduce(Promise.unitPromise(),
                              (previous, action) -> previous.flatMap(_ -> applyDerived(action, leader, snapshot)),
                              (_, later) -> later);
    }

    // FER (degrade forward): a refused or failed write for one derived community is dropped, and the pass carries on to the next. Guarantee: the
    // community is left exactly as committed, because every write is an expected-value transaction; nothing is half-applied. Mechanism: the
    // next pass (every 10 s) recomputes the actions from the committed state, so a dropped write is retried by construction.
    private Promise<Unit> applyDerived(DerivedCommunityPlan.Action action,
                                       LeaderValue leader,
                                       Map<AetherKey, AetherValue> snapshot) {
        return (switch (action) {
            case DerivedCommunityPlan.Action.Restamp restamp -> rewriteCommunity(restamp.communityId(),
                                                                                 community -> community.withTargetSize(restamp.targetSize()),
                                                                                 leader,
                                                                                 snapshot);
            case DerivedCommunityPlan.Action.BeginDissolve begin -> rewriteCommunity(begin.communityId(),
                                                                                     community -> community.withState(org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVING),
                                                                                     leader,
                                                                                     snapshot);
            case DerivedCommunityPlan.Action.Reopen reopen -> rewriteCommunity(reopen.communityId(),
                                                                               community -> community.withState(org.pragmatica.aether.slice.kvstore.CommunityState.FORMING),
                                                                               leader,
                                                                               snapshot);
            case DerivedCommunityPlan.Action.CompleteDissolve complete -> markDissolved(complete.communityId(),
                                                                                        leader,
                                                                                        snapshot);
        }).recover(_ -> Unit.unit());
    }

    private Promise<Unit> rewriteCommunity(String communityId,
                                           Function<AetherValue.CommunityValue, AetherValue.CommunityValue> change,
                                           LeaderValue leader,
                                           Map<AetherKey, AetherValue> snapshot) {
        var key = new AetherKey.CommunityKey(communityId);

        return store.getTyped(key, AetherValue.CommunityValue.class)
                    .fold(Promise::unitPromise,
                          before -> {
                              var id = UUID.randomUUID().toString();
                              var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                    id,
                                                                                                    leader,
                                                                                                    reservationGuardsFromSnapshot(snapshot),
                                                                                                    List.of(new KVCommand.Mutation<>(key,
                                                                                                                                     Option.some(before),
                                                                                                                                     Option.some(change.apply(before)))));

                              return apply.apply(List.of(command))
                                          .map(results -> accepted(results, id))
                                          .mapToUnit();
                          });
    }

    private Promise<Unit> reconcileIdentity(String identity) {
        var snapshot = aetherSnapshot();

        return configuration(snapshot).fold(Promise::unitPromise,
                                            config -> Option.option(config.communities().get(identity)).fold(() -> currentLeader().fold(Promise::unitPromise,
                                                                                                                                        leader -> operation(identity).filter(CommunityPlacementOperationValue::active)
                                                                                                                                                           .fold(Promise::unitPromise,
                                                                                                                                                                 value -> advance(config,
                                                                                                                                                                                  value,
                                                                                                                                                                                  leader))),
                                                                                                             policy -> reconcileCommunity(config,
                                                                                                                                          policy,
                                                                                                                                          snapshot)));
    }

    private Promise<Unit> reconcileCommunity(ClusterBootstrapConfig config,
                                             CommunityPlacement policy,
                                             Map<AetherKey, AetherValue> snapshot) {
        return currentLeader().fold(Promise::unitPromise,
                                    leader -> alignCommunity(policy, leader, snapshot).flatMap(accepted -> {
                                        if (!accepted) {
                                            return Promise.unitPromise();
                                        }

                                        return operation(policy.id()).filter(CommunityPlacementOperationValue::active)
                                                        .fold(() -> reserve(config, policy, leader, snapshot),
                                                              value -> advance(config, value, leader));
                                    }));
    }

    private Promise<Boolean> alignCommunity(CommunityPlacement policy,
                                            LeaderValue leader,
                                            Map<AetherKey, AetherValue> snapshot) {
        var key = new AetherKey.CommunityKey(policy.id());
        var existing = store.getTyped(key, AetherValue.CommunityValue.class);

        if (existing.filter(value -> value.state() == org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVING || value.state() == org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVED)
                    .isPresent()) {
            return Promise.success(false);
        }

        if (existing.filter(value -> value.targetSize() == policy.targetSize()).isPresent()) {
            return Promise.success(true);
        }

        var desired = existing.map(value -> new AetherValue.CommunityValue(value.sourceName(),
                                                                           value.role(),
                                                                           policy.targetSize(),
                                                                           value.state(),
                                                                           value.createdAt(),
                                                                           value.dissolvedAt()))
                              .or(() -> AetherValue.CommunityValue.communityValue("",
                                                                                  AetherValue.ActivationDirectiveValue.WORKER,
                                                                                  policy.targetSize()));
        var id = UUID.randomUUID().toString();
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                      existing.map(value -> value),
                                                                      Option.some(desired));
        var guards = List.of(new KVCommand.ReadWitness<AetherKey>(AetherKey.ClusterConfigKey.CURRENT,
                                                                  Option.option(snapshot.get(AetherKey.ClusterConfigKey.CURRENT)).map(value -> (Object) value)));
        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, id, leader, guards, List.of(mutation));

        return apply.apply(List.of(command))
                    .map(results -> accepted(results, id));
    }

    private static boolean accepted(List<Object> results, String id) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .anyMatch(result -> result.transactionId()
                                                .equals(id) && result.accepted());
    }

    private Option<LeaderValue> currentLeader() {
        return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                    .filter(leader -> activeLeader.getAsBoolean() && leader.leader()
                                                                           .equals(self));
    }

    private Option<CommunityPlacementOperationValue> operation(String community) {
        return store.getTyped(new AetherKey.CommunityPlacementOperationKey(community),
                              CommunityPlacementOperationValue.class);
    }

    private Promise<Unit> advance(ClusterBootstrapConfig config,
                                  CommunityPlacementOperationValue operation,
                                  LeaderValue leader) {
        if (!operation.issuer().equals(leader)) {
            var adopted = operation.withIssuer(leader);

            return transition(operation, adopted).flatMap(accepted -> accepted && currentLeader().filter(leader::equals)
                                                                                               .isPresent()
                                                                      ? advance(config, adopted, leader)
                                                                      : Promise.unitPromise());
        }

        var source = Option.option(config.sources().get(operation.targetSource()));

        if (source.filter(profile -> actuator.sourceBinding(profile.name())
                                             .option()
                                             .filter(operation.sourceBinding()::equals)
                                             .isPresent())
                  .isEmpty()) {
            return uncertain(operation,
                             leader,
                             operation.phase(),
                             "Source identity changed or disappeared; restore the recorded binding to resume");
        }

        return switch (operation.phase()) {
            case RESERVED -> beginCreate(config, operation, leader);
            case CREATE_REQUESTED -> refusedTarget(operation)
                                     ? capacityRefused(config, operation, leader)
                                     : observedTarget(operation).isPresent()
                                       ? createAccepted(config, operation, leader)
                                       : uncertain(operation,
                                                   leader,
                                                   PlacementOperationPhase.CREATE_UNCERTAIN,
                                                   "Create outcome was not committed; reconcile provider inventory before resuming");
            case AWAITING_READY -> targetReady(operation)
                                   ? beginDrain(operation, leader)
                                   : readinessExpired(config, operation)
                                     ? uncertain(operation,
                                                 leader,
                                                 PlacementOperationPhase.READINESS_DELAYED,
                                                 "Reserved replacement did not become ready; reservation retained")
                                     : Promise.unitPromise();
            case DRAIN_REQUESTED -> clock.getAsLong() - operation.phaseChangedAt() > drainTimeout.millis()
                                    ? uncertain(operation,
                                                leader,
                                                PlacementOperationPhase.DRAIN_UNCERTAIN,
                                                "Drain acknowledgement deadline expired")
                                    : targetReady(operation)
                                      ? actuator.drain(operation)
                                      : Promise.unitPromise();
            case DRAINED -> beginTerminate(operation, leader);
            case TERMINATING -> finishTermination(operation, leader);
            case CREATE_UNCERTAIN -> refusedTarget(operation)
                                     ? capacityRefused(config, operation, leader)
                                     : observedTarget(operation).isPresent()
                                       ? createAccepted(config, operation, leader)
                                       : Promise.unitPromise();
            case READINESS_DELAYED -> targetReady(operation)
                                      ? beginDrain(operation, leader)
                                      : Promise.unitPromise();
            case BLOCKED -> observedTarget(operation).isPresent()
                            ? createAccepted(config, operation, leader)
                            : Promise.unitPromise();
            case COMPLETE, DRAIN_UNCERTAIN, UNKNOWN -> Promise.unitPromise();
        };
    }

    private boolean refusedTarget(CommunityPlacementOperationValue operation) {
        return store.getTyped(new AetherKey.CapacityReservationKey(operation.targetNode()),
                              AetherValue.CapacityReservationValue.class)
                    .filter(value -> value.phase() == AetherValue.CapacityReservationPhase.RELEASED
                                     && value.sourceName()
                                             .equals(operation.targetSource())
                                     && value.sourceBinding()
                                             .equals(operation.sourceBinding()))
                    .isPresent();
    }

    private boolean readinessExpired(ClusterBootstrapConfig config, CommunityPlacementOperationValue operation) {
        var timeout = config.sources()
                            .get(operation.targetSource())
                            .replacementCeiling()
                            .or(org.pragmatica.aether.config.cluster.SourceProfile.DEFAULT_REPLACEMENT_CEILING)
                            .millis();

        return clock.getAsLong() - operation.phaseChangedAt() > timeout;
    }

    private Promise<Unit> beginCreate(ClusterBootstrapConfig config,
                                      CommunityPlacementOperationValue operation,
                                      LeaderValue leader) {
        if (store.get(new AetherKey.NodePlacementKey(operation.targetNode())).isPresent()) {
            return observedTarget(operation).isPresent()
                   ? createAccepted(config, operation, leader)
                   : uncertain(operation,
                               leader,
                               PlacementOperationPhase.BLOCKED,
                               "Reserved identity observed in a different location");
        }

        var requested = operation.withPhase(PlacementOperationPhase.CREATE_REQUESTED,
                                            leader,
                                            operation.detail(),
                                            clock.getAsLong());

        return transition(operation, requested).flatMap(accepted -> accepted && currentLeader().filter(leader::equals)
                                                                                             .isPresent()
                                                                    ? actuator.create(requested)
                                                                              .flatMap(_ -> createAccepted(config,
                                                                                                           requested,
                                                                                                           leader))
                                                                              .fold(result -> result.fold(cause -> cause instanceof CapacityControlledLifecycle.AdmissionFailure
                                                                                                                   ? deferCapacity(requested,
                                                                                                                                   leader,
                                                                                                                                   cause.message())
                                                                                                                   : definitiveRefusal(cause)
                                                                                                                     ? capacityRefused(config,
                                                                                                                                       requested,
                                                                                                                                       leader)
                                                                                                                     : uncertain(requested,
                                                                                                                                 leader,
                                                                                                                                 PlacementOperationPhase.CREATE_UNCERTAIN,
                                                                                                                                 cause.message()),
                                                                                                          Promise::success))
                                                                    : Promise.unitPromise());
    }

    private static boolean definitiveRefusal(org.pragmatica.lang.Cause cause) {
        return cause instanceof org.pragmatica.aether.environment.EnvironmentError.CapacityUnavailable || cause instanceof org.pragmatica.aether.environment.EnvironmentError.NodeCapExceeded;
    }

    private static Comparator<CommunityPlacement.Location> locationOrder() {
        return Comparator.comparing(CommunityPlacement.Location::source).thenComparing(location -> location.zone()
                                                                                                           .or(""));
    }

    private static AetherKey.CommunityPlacementAvailabilityKey availabilityKey(String community,
                                                                               String source,
                                                                               Option<String> zone) {
        return new AetherKey.CommunityPlacementAvailabilityKey(community, source, zone);
    }

    private static Option<AetherValue.CommunityPlacementAvailabilityValue> availability(CommunityPlacement policy,
                                                                                        CommunityPlacement.Location location,
                                                                                        Map<AetherKey, AetherValue> snapshot) {
        return Option.option(snapshot.get(availabilityKey(policy.id(),
                                                          location.source(),
                                                          location.zone())))
                     .filter(AetherValue.CommunityPlacementAvailabilityValue.class::isInstance)
                     .map(AetherValue.CommunityPlacementAvailabilityValue.class::cast)
                     .filter(value -> value.policyIdentity()
                                           .equals(CommunityPlacementAvailability.policyIdentity(policy)));
    }

    private static boolean releasedRefusal(AetherValue.CommunityPlacementAvailabilityValue refusal,
                                           String source,
                                           Map<AetherKey, AetherValue> snapshot) {
        var reservation = Option.option(snapshot.get(new AetherKey.CapacityReservationKey(refusal.refusedNode())));

        return reservation.isEmpty() || reservation.filter(value -> value instanceof AetherValue.CapacityReservationValue allocation
                                                                    && allocation.phase() == AetherValue.CapacityReservationPhase.RELEASED
                                                                    && allocation.sourceName()
                                                                                 .equals(source)
                                                                    && allocation.sourceBinding()
                                                                                 .equals(refusal.sourceBinding()))
                                                   .isPresent();
    }

    private static boolean unavailable(CommunityPlacement policy,
                                       CommunityPlacement.Location location,
                                       Option<String> binding,
                                       Map<AetherKey, AetherValue> snapshot) {
        return availability(policy, location, snapshot).filter(value -> binding.isEmpty() || binding.filter(value.sourceBinding()::equals)
                                                                                                    .isPresent() || !releasedRefusal(value,
                                                                                                                                     location.source(),
                                                                                                                                     snapshot))
                           .isPresent();
    }

    private boolean probeDue(CommunityPlacement policy,
                             CommunityPlacement.Location location,
                             Option<String> binding,
                             Map<AetherKey, AetherValue> snapshot) {
        return availability(policy, location, snapshot).filter(value -> clock.getAsLong() - value.refusedAt() >= CommunityPlacementAvailability.retryDelay(value.attempts()).millis())
                           .filter(value -> binding.filter(value.sourceBinding()::equals)
                                                   .isPresent())
                           .filter(value -> releasedRefusal(value,
                                                            location.source(),
                                                            snapshot))
                           .isPresent();
    }

    private Promise<Unit> capacityRefused(ClusterBootstrapConfig config,
                                          CommunityPlacementOperationValue operation,
                                          LeaderValue leader) {
        var reservationKey = new AetherKey.CapacityReservationKey(operation.targetNode());
        var reservation = store.getTyped(reservationKey, AetherValue.CapacityReservationValue.class);

        if (reservation.filter(value -> value.phase() != AetherValue.CapacityReservationPhase.RELEASED || !value.sourceName()
                                                                                                                .equals(operation.targetSource()) || !value.sourceBinding()
                                                                                                                                                           .equals(operation.sourceBinding()))
                       .isPresent()) {
            return uncertain(operation,
                             leader,
                             PlacementOperationPhase.CREATE_UNCERTAIN,
                             "Definitive refusal has unresolved capacity accounting");
        }

        var policy = Option.option(config.communities().get(operation.communityId()));

        if (policy.isEmpty()) return Promise.unitPromise();

        var key = availabilityKey(operation.communityId(), operation.targetSource(), operation.targetZone());
        var before = store.getTyped(key, AetherValue.CommunityPlacementAvailabilityValue.class);
        var identity = CommunityPlacementAvailability.policyIdentity(policy.unwrap());
        int attempts = before.filter(value -> value.policyIdentity()
                                                   .equals(identity) && value.sourceBinding()
                                                                             .equals(operation.sourceBinding()))
                             .map(value -> Math.min(31,
                                                    value.attempts() + 1))
                             .or(1);
        var refused = new AetherValue.CommunityPlacementAvailabilityValue(identity,
                                                                          operation.sourceBinding(),
                                                                          operation.targetNode(),
                                                                          clock.getAsLong(),
                                                                          attempts);
        var completed = operation.withPhase(PlacementOperationPhase.COMPLETE,
                                            leader,
                                            "Provider definitively refused capacity; eligible discretionary capacity may fall back",
                                            clock.getAsLong());

        return commitAvailability(operation,
                                  completed,
                                  key,
                                  before,
                                  Option.some(refused),
                                  List.of(new KVCommand.ReadWitness<AetherKey>(reservationKey,
                                                                               reservation.map(value -> (Object) value)),
                                          new KVCommand.ReadWitness<AetherKey>(AetherKey.ClusterConfigKey.CURRENT,
                                                                               store.get(AetherKey.ClusterConfigKey.CURRENT)
                                                                                    .map(value -> (Object) value)))).onSuccess(accepted -> {
                                                                                                                                   if (accepted) escalation.accept(completed);
                                                                                                                               })
                                 .mapToUnit();
    }

    private Promise<Unit> createAccepted(ClusterBootstrapConfig config,
                                         CommunityPlacementOperationValue operation,
                                         LeaderValue leader) {
        var snapshot = aetherSnapshot();
        var key = availabilityKey(operation.communityId(), operation.targetSource(), operation.targetZone());
        var before = Option.option(snapshot.get(key))
                           .filter(AetherValue.CommunityPlacementAvailabilityValue.class::isInstance)
                           .map(AetherValue.CommunityPlacementAvailabilityValue.class::cast);
        var next = operation.withPhase(PlacementOperationPhase.AWAITING_READY, leader, "", clock.getAsLong());

        if (before.isEmpty()) return transition(operation, next).mapToUnit();

        if (before.filter(value -> releasedRefusal(value, operation.targetSource(), snapshot)).isEmpty()) {
            return uncertain(operation,
                             leader,
                             PlacementOperationPhase.BLOCKED,
                             "Earlier refused attempt has unresolved capacity accounting");
        }

        var reservationKey = new AetherKey.CapacityReservationKey(before.unwrap().refusedNode());

        return commitAvailability(operation,
                                  next,
                                  key,
                                  before,
                                  Option.none(),
                                  List.of(new KVCommand.ReadWitness<AetherKey>(reservationKey,
                                                                               Option.option(snapshot.get(reservationKey)).map(value -> (Object) value)))).mapToUnit();
    }

    private Promise<Boolean> commitAvailability(CommunityPlacementOperationValue before,
                                                CommunityPlacementOperationValue after,
                                                AetherKey.CommunityPlacementAvailabilityKey availabilityKey,
                                                Option<AetherValue.CommunityPlacementAvailabilityValue> expected,
                                                Option<AetherValue.CommunityPlacementAvailabilityValue> replacement,
                                                List<KVCommand.ReadWitness<AetherKey>> guards) {
        var key = new AetherKey.CommunityPlacementOperationKey(before.communityId());
        var id = UUID.randomUUID().toString();
        var mutations = List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                               Option.some(before),
                                                                               Option.some(after)),
                                new KVCommand.Mutation<AetherKey, AetherValue>(availabilityKey,
                                                                               expected.map(value -> value),
                                                                               replacement.map(value -> value)));
        // A failed create must never run COMPLETE's normal previous-worker removal side effect.
        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, id, after.issuer(), guards, mutations);

        return apply.apply(List.of(command))
                    .map(results -> accepted(results, id));
    }

    private Promise<Unit> deferCapacity(CommunityPlacementOperationValue operation, LeaderValue leader, String detail) {
        var deferred = operation.withPhase(PlacementOperationPhase.RESERVED, leader, detail, clock.getAsLong());

        return transition(operation, deferred).onSuccess(accepted -> {
                                                             if (accepted && !operation.detail()
                                                                                       .equals(detail)) {
                                                                 escalation.accept(deferred);
                                                             }
                                                         })
                         .mapToUnit();
    }

    private Promise<Unit> beginDrain(CommunityPlacementOperationValue operation, LeaderValue leader) {
        if (operation.previousNode().isEmpty()) {
            return transition(operation,
                              operation.withPhase(PlacementOperationPhase.COMPLETE, leader, "", clock.getAsLong())).mapToUnit();
        }

        if (operation.previousNode()
                     .filter(node -> store.getTyped(new AetherKey.ActivationDirectiveKey(node),
                                                    AetherValue.ActivationDirectiveValue.class)
                                          .filter(value -> value.role()
                                                                .equals(AetherValue.ActivationDirectiveValue.WORKER) && value.communityId()
                                                                                                                             .equals(operation.communityId()))
                                          .isPresent())
                     .isEmpty()) {
            return uncertain(operation,
                             leader,
                             PlacementOperationPhase.BLOCKED,
                             "Previous worker assignment changed before drain");
        }

        return actuator.retirementSafe(operation)
                       .flatMap(safe -> safe && targetReady(operation) && currentLeader().filter(leader::equals)
                                                                                       .isPresent()
                                        ? commitDrain(operation, leader)
                                        : retirementDelayed(operation, leader));
    }

    private Promise<Unit> retirementDelayed(CommunityPlacementOperationValue operation, LeaderValue leader) {
        return clock.getAsLong() - operation.phaseChangedAt() > drainTimeout.millis()
               ? uncertain(operation,
                           leader,
                           PlacementOperationPhase.READINESS_DELAYED,
                           "Previous worker evacuation is delayed; retirement safety proof is still required")
               : Promise.unitPromise();
    }

    private Promise<Unit> commitDrain(CommunityPlacementOperationValue operation, LeaderValue leader) {
        var draining = operation.withPhase(PlacementOperationPhase.DRAIN_REQUESTED, leader, "", clock.getAsLong());

        return transition(operation, draining).flatMap(accepted -> accepted && targetReady(operation) && currentLeader().filter(leader::equals)
                                                                                                                      .isPresent()
                                                                   ? actuator.drain(draining)
                                                                   : Promise.unitPromise());
    }

    private Promise<Unit> beginTerminate(CommunityPlacementOperationValue operation, LeaderValue leader) {
        var terminating = operation.withPhase(PlacementOperationPhase.TERMINATING, leader, "", clock.getAsLong());

        return transition(operation, terminating).flatMap(accepted -> accepted && currentLeader().filter(leader::equals)
                                                                                               .isPresent()
                                                                      ? finishTermination(terminating, leader)
                                                                      : Promise.unitPromise());
    }

    private Promise<Unit> finishTermination(CommunityPlacementOperationValue operation, LeaderValue leader) {
        return actuator.previousInstanceExists(operation)
                       .flatMap(exists -> {
                                    if (currentLeader().filter(leader::equals)
                                                     .isEmpty()) {
                                        return Promise.unitPromise();
                                    }

                                    return exists
                                           ? terminateIfSafe(operation, leader)
                                           : transition(operation,
                                                        operation.withPhase(PlacementOperationPhase.COMPLETE,
                                                                            leader,
                                                                            "",
                                                                            clock.getAsLong())).mapToUnit();
                                });
    }

    private Promise<Unit> terminateIfSafe(CommunityPlacementOperationValue operation, LeaderValue leader) {
        if (!targetReady(operation)) {
            return Promise.unitPromise();
        }

        return actuator.retirementSafe(operation)
                       .flatMap(safe -> safe && targetReady(operation) && currentLeader().filter(leader::equals)
                                                                                       .isPresent()
                                        ? actuator.terminate(operation)
                                        : Promise.unitPromise());
    }

    private Promise<Unit> uncertain(CommunityPlacementOperationValue operation,
                                    LeaderValue leader,
                                    PlacementOperationPhase phase,
                                    String reason) {
        if (operation.phase() == phase && operation.detail().equals(reason)) return Promise.unitPromise();

        var uncertain = operation.withPhase(phase, leader, reason, clock.getAsLong());

        return transition(operation, uncertain).onSuccess(accepted -> {
                             if (accepted) {
                                 escalation.accept(uncertain);
                             }
                         })
                         .mapToUnit();
    }

    private Option<AetherValue.NodePlacementValue> observedTarget(CommunityPlacementOperationValue operation) {
        return store.getTyped(new AetherKey.NodePlacementKey(operation.targetNode()),
                              AetherValue.NodePlacementValue.class)
                    .filter(value -> value.sourceName()
                                          .equals(operation.targetSource()))
                    .filter(value -> operation.targetZone()
                                              .fold(() -> true,
                                                    zone -> value.observedZone()
                                                                 .filter(zone::equals)
                                                                 .isPresent()));
    }

    private boolean targetReady(CommunityPlacementOperationValue operation) {
        if (operation.previousNode().filter(operation.targetNode()::equals).isPresent()) {
            return true;
        }

        return ready.test(operation.targetNode())
               && observedTarget(operation).isPresent()
               && store.getTyped(new AetherKey.ActivationDirectiveKey(operation.targetNode()),
                                 AetherValue.ActivationDirectiveValue.class)
                       .filter(value -> value.role()
                                             .equals(AetherValue.ActivationDirectiveValue.WORKER) && value.communityId()
                                                                                                          .equals(operation.communityId()))
                       .isPresent();
    }

    @Override
    public Promise<Boolean> onDrainCompleted(NodeId sender, String operationId) {
        return currentLeader().fold(() -> Promise.success(false),
                                    leader -> matchingDrain(sender, operationId).fold(() -> Promise.success(false),
                                                                                      operation -> switch (operation.phase()) {
                                                                                          case DRAINED, TERMINATING, COMPLETE -> Promise.success(true);
                                                                                          case DRAIN_REQUESTED, DRAIN_UNCERTAIN -> transition(operation,
                                                                                                                                              operation.withPhase(PlacementOperationPhase.DRAINED,
                                                                                                                                                                  leader,
                                                                                                                                                                  "",
                                                                                                                                                                  clock.getAsLong()));
                                                                                          default -> Promise.success(false);
                                                                                      }));
    }

    private Option<CommunityPlacementOperationValue> matchingDrain(NodeId sender, String operationId) {
        return Option.from(aetherSnapshot().values()
                                         .stream()
                                         .filter(CommunityPlacementOperationValue.class::isInstance)
                                         .map(CommunityPlacementOperationValue.class::cast)
                                         .filter(value -> value.operationId()
                                                               .equals(operationId) && value.previousNode()
                                                                                            .filter(sender::equals)
                                                                                            .isPresent())
                                         .findFirst());
    }

    private Promise<Boolean> transition(CommunityPlacementOperationValue before,
                                        CommunityPlacementOperationValue after) {
        return commit(after, Option.some(before), List.of());
    }

    private Promise<Boolean> commit(CommunityPlacementOperationValue value,
                                    Option<CommunityPlacementOperationValue> expected,
                                    List<KVCommand.ReadWitness<AetherKey>> guards) {
        var key = new AetherKey.CommunityPlacementOperationKey(value.communityId());
        var id = UUID.randomUUID().toString();
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                      expected.map(previous -> previous),
                                                                      Option.some(value));
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        mutations.add(mutation);
        if (value.phase() == PlacementOperationPhase.COMPLETE) {
            value.previousNode()
                 .onPresent(node -> {
                                addRemoval(mutations,
                                           new AetherKey.ActivationDirectiveKey(node));
                                addRemoval(mutations,
                                           new AetherKey.NodePlacementKey(node));
                            });
        }

        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, id, value.issuer(), guards, mutations);

        return apply.apply(List.of(command))
                    .map(results -> accepted(results, id));
    }

    @Contract
    private void addRemoval(List<KVCommand.Mutation<AetherKey, AetherValue>> mutations, AetherKey key) {
        store.get(key)
             .onPresent(value -> mutations.add(new KVCommand.Mutation<>(key,
                                                                        Option.some(value),
                                                                        Option.none())));
    }

    private Promise<Unit> reserve(ClusterBootstrapConfig config,
                                  CommunityPlacement policy,
                                  LeaderValue leader,
                                  Map<AetherKey, AetherValue> snapshot) {
        var members = communityMembers(snapshot, policy.id());

        if (policy.targetSize() == 0) {
            return Option.from(members.entrySet()
                                      .stream()
                                      .sorted(Map.Entry.comparingByKey(Comparator.comparing(NodeId::id)))
                                      .findFirst()).fold(() -> markDissolved(policy.id(), leader, snapshot),
                                                         member -> reserveRetirement(config,
                                                                                     policy,
                                                                                     leader,
                                                                                     snapshot,
                                                                                     member));
        }

        var bindings = policy.locations()
                             .stream()
                             .collect(Collectors.toMap(Function.identity(),
                                                       location -> actuator.sourceBinding(config.sources()
                                                                                                .get(location.source())
                                                                                                .name())));
        var unavailable = policy.locations()
                                .stream()
                                .filter(location -> unavailable(policy,
                                                                location,
                                                                bindings.get(location).option(),
                                                                snapshot))
                                .collect(Collectors.toSet());
        var effective = CommunityPlacementAvailability.effectiveCounts(policy, unavailable);
        var ordinary = policy.locations()
                             .stream()
                             .filter(location -> !unavailable.contains(location))
                             .filter(location -> matching(members, location).size() < effective.get(location))
                             .sorted(locationOrder())
                             .findFirst();
        var probe = ordinary.isPresent()
                    ? java.util.Optional.<CommunityPlacement.Location> empty()
                    : policy.locations()
                            .stream()
                            .filter(unavailable::contains)
                            .filter(location -> probeDue(policy,
                                                         location,
                                                         bindings.get(location).option(),
                                                         snapshot))
                            .filter(location -> matching(members, location).size() < policy.desiredCounts()
                                                                                           .get(location))
                            .sorted(locationOrder())
                            .findFirst();
        var probingUnavailable = new HashSet<>(unavailable);

        probe.ifPresent(probingUnavailable::remove);
        var desired = CommunityPlacementAvailability.effectiveCounts(policy, probingUnavailable);
        var deficit = ordinary.isPresent()
                      ? ordinary
                      : probe;
        var shielded = protectedNodes.get().get();
        var surge = surgeNodes.get().get();
        var counted = members.entrySet()
                             .stream()
                             .filter(entry -> !surge.contains(entry.getKey()))
                             .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var previous = members.entrySet()
                              .stream()
                              .filter(entry -> !shielded.contains(entry.getKey()))
                              .filter(entry -> policy.locations()
                                                     .stream()
                                                     .noneMatch(location -> matches(entry.getValue(),
                                                                                    location)) || policy.locations()
                                                                                                        .stream()
                                                                                                        .filter(location -> matches(entry.getValue(),
                                                                                                                                    location))
                                                                                                        .anyMatch(location -> matching(counted,
                                                                                                                                       location).size() > desired.get(location)))
                              .sorted(Map.Entry.comparingByKey(Comparator.comparing(NodeId::id)))
                              .findFirst();

        if (deficit.isEmpty()) {
            if (!unavailable.isEmpty()) return Promise.unitPromise();

            return previous.map(entry -> reserveReduction(config, policy, leader, snapshot, members, entry))
                           .orElseGet(Promise::unitPromise);
        }

        if (!hasCapacity(config, snapshot)) {
            return Promise.unitPromise();
        }

        var destination = deficit.get();
        var id = UUID.randomUUID().toString();
        var target = new NodeId("placement-" + id);

        return bindings.get(destination)
                       .fold(cause -> cause.promise(),
                             binding -> reserveOperation(config,
                                                         policy,
                                                         leader,
                                                         snapshot,
                                                         destination,
                                                         Option.from(previous),
                                                         id,
                                                         target,
                                                         binding));
    }

    private Promise<Unit> reserveRetirement(ClusterBootstrapConfig config,
                                            CommunityPlacement policy,
                                            LeaderValue leader,
                                            Map<AetherKey, AetherValue> snapshot,
                                            Map.Entry<NodeId, AetherValue.NodePlacementValue> previous) {
        var source = org.pragmatica.aether.environment.SourceName.sourceNameOrDefault(previous.getValue().sourceName());

        return actuator.sourceBinding(source)
                       .fold(cause -> cause.promise(),
                             binding -> {
                                 var value = new CommunityPlacementOperationValue(UUID.randomUUID().toString(),
                                                                                  policy.id(),
                                                                                  previous.getKey(),
                                                                                  source.value(),
                                                                                  previous.getValue().observedZone(),
                                                                                  binding,
                                                                                  Option.some(previous.getKey()),
                                                                                  source.value(),
                                                                                  PlacementOperationPhase.AWAITING_READY,
                                                                                  leader,
                                                                                  clock.getAsLong(),
                                                                                  clock.getAsLong(),
                                                                                  "Community retirement");

                                 return commit(value,
                                               operation(policy.id()),
                                               reservationGuards(config, snapshot)).mapToUnit();
                             });
    }

    private Promise<Unit> markDissolved(String communityId, LeaderValue leader, Map<AetherKey, AetherValue> snapshot) {
        var key = new AetherKey.CommunityKey(communityId);

        return store.getTyped(key, AetherValue.CommunityValue.class)
                    .fold(Promise::unitPromise,
                          before -> {
                              var after = new AetherValue.CommunityValue(before.sourceName(),
                                                                         before.role(),
                                                                         0,
                                                                         org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVED,
                                                                         before.createdAt(),
                                                                         Option.some(clock.getAsLong()));
                              var id = UUID.randomUUID().toString();
                              var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                    id,
                                                                                                    leader,
                                                                                                    reservationGuardsFromSnapshot(snapshot),
                                                                                                    List.of(new KVCommand.Mutation<>(key,
                                                                                                                                     Option.some(before),
                                                                                                                                     Option.some(after))));

                              return apply.apply(List.of(command))
                                          .flatMap(results -> results.stream()
                                                                     .filter(KVCommand.TransactionResult.class::isInstance)
                                                                     .map(KVCommand.TransactionResult.class::cast)
                                                                     .anyMatch(result -> result.transactionId()
                                                                                               .equals(id) && result.accepted())
                                                              ? Promise.unitPromise()
                                                              : HierarchyStateWriter.Refusal.CONFLICT.promise());
                          });
    }

    private List<KVCommand.ReadWitness<AetherKey>> reservationGuardsFromSnapshot(Map<AetherKey, AetherValue> snapshot) {
        var guards = new ArrayList<KVCommand.ReadWitness<AetherKey>>();

        guards.add(new KVCommand.ReadWitness<>(AetherKey.ClusterConfigKey.CURRENT,
                                               Option.option(snapshot.get(AetherKey.ClusterConfigKey.CURRENT))));
        snapshot.forEach((key, value) -> {
            if (key instanceof AetherKey.CommunityPlacementOperationKey) {
                guards.add(new KVCommand.ReadWitness<>(key, Option.some(value)));
            }
        });

        return guards;
    }

    private Promise<Unit> reserveReduction(ClusterBootstrapConfig config,
                                           CommunityPlacement policy,
                                           LeaderValue leader,
                                           Map<AetherKey, AetherValue> snapshot,
                                           Map<NodeId, AetherValue.NodePlacementValue> members,
                                           Map.Entry<NodeId, AetherValue.NodePlacementValue> previous) {
        var survivor = members.entrySet()
                              .stream()
                              .filter(entry -> !entry.getKey()
                                                     .equals(previous.getKey())
                                               && ready.test(entry.getKey())
                                               && policy.locations()
                                                        .stream()
                                                        .anyMatch(location -> matches(entry.getValue(),
                                                                                      location)))
                              .sorted(Map.Entry.comparingByKey(Comparator.comparing(NodeId::id)))
                              .findFirst();

        if (survivor.isEmpty()) {
            return Promise.unitPromise();
        }

        var target = survivor.get();

        return actuator.sourceBinding(config.sources().get(target.getValue().sourceName()).name())
                       .fold(cause -> cause.promise(),
                             binding -> {
                                 var value = new CommunityPlacementOperationValue(UUID.randomUUID().toString(),
                                                                                  policy.id(),
                                                                                  target.getKey(),
                                                                                  target.getValue().sourceName(),
                                                                                  target.getValue().observedZone(),
                                                                                  binding,
                                                                                  Option.some(previous.getKey()),
                                                                                  previous.getValue().sourceName(),
                                                                                  PlacementOperationPhase.AWAITING_READY,
                                                                                  leader,
                                                                                  clock.getAsLong(),
                                                                                  clock.getAsLong(),
                                                                                  "Scale down");

                                 return commit(value,
                                               operation(policy.id()),
                                               reservationGuards(config, snapshot)).mapToUnit();
                             });
    }

    private Promise<Unit> reserveOperation(ClusterBootstrapConfig config,
                                           CommunityPlacement policy,
                                           LeaderValue leader,
                                           Map<AetherKey, AetherValue> snapshot,
                                           CommunityPlacement.Location destination,
                                           Option<Map.Entry<NodeId, AetherValue.NodePlacementValue>> previous,
                                           String id,
                                           NodeId target,
                                           String binding) {
        var value = new CommunityPlacementOperationValue(id,
                                                         policy.id(),
                                                         target,
                                                         destination.source(),
                                                         destination.zone(),
                                                         binding,
                                                         previous.map(Map.Entry::getKey),
                                                         previous.map(entry -> entry.getValue()
                                                                                    .sourceName()).or(""),
                                                         PlacementOperationPhase.RESERVED,
                                                         leader,
                                                         clock.getAsLong(),
                                                         clock.getAsLong(),
                                                         "");
        var guards = reservationGuards(config, snapshot);

        value.previousNode()
             .onPresent(node -> {
                            var activation = new AetherKey.ActivationDirectiveKey(node);
                            var placement = new AetherKey.NodePlacementKey(node);

                            guards.add(new KVCommand.ReadWitness<>(activation,
                                                                   Option.option(snapshot.get(activation))));
                            guards.add(new KVCommand.ReadWitness<>(placement,
                                                                   Option.option(snapshot.get(placement))));
                        });

        return commit(value, operation(policy.id()), guards).mapToUnit();
    }

    private List<KVCommand.ReadWitness<AetherKey>> reservationGuards(ClusterBootstrapConfig config,
                                                                     Map<AetherKey, AetherValue> snapshot) {
        var guards = new ArrayList<KVCommand.ReadWitness<AetherKey>>();

        guards.add(new KVCommand.ReadWitness<>(AetherKey.ClusterConfigKey.CURRENT,
                                               Option.option(snapshot.get(AetherKey.ClusterConfigKey.CURRENT))));
        for (var community : config.communities().keySet()) {
            var key = new AetherKey.CommunityPlacementOperationKey(community);

            guards.add(new KVCommand.ReadWitness<>(key,
                                                   Option.option(snapshot.get(key))));
            for (var location : config.communities().get(community).locations()) {
                var availabilityKey = availabilityKey(community, location.source(), location.zone());

                guards.add(new KVCommand.ReadWitness<>(availabilityKey,
                                                       Option.option(snapshot.get(availabilityKey)).map(value -> (Object) value)));
                if (snapshot.get(availabilityKey) instanceof AetherValue.CommunityPlacementAvailabilityValue refusal) {
                    var reservationKey = new AetherKey.CapacityReservationKey(refusal.refusedNode());

                    guards.add(new KVCommand.ReadWitness<>(reservationKey,
                                                           Option.option(snapshot.get(reservationKey)).map(value -> (Object) value)));
                }
            }
        }

        return guards;
    }

    private boolean hasCapacity(ClusterBootstrapConfig config, Map<AetherKey, AetherValue> snapshot) {
        var nodes = new HashSet<>(admittedNodes.get());

        snapshot.keySet()
                .stream()
                .filter(_ -> !snapshot.containsKey(AetherKey.CapacityLedgerKey.INSTANCE))
                .filter(AetherKey.NodePlacementKey.class::isInstance)
                .map(AetherKey.NodePlacementKey.class::cast)
                .map(AetherKey.NodePlacementKey::nodeId)
                .forEach(nodes::add);
        snapshot.keySet()
                .stream()
                .filter(AetherKey.CapacityReservationKey.class::isInstance)
                .map(AetherKey.CapacityReservationKey.class::cast)
                .map(AetherKey.CapacityReservationKey::nodeId)
                .forEach(nodes::add);
        snapshot.values()
                .stream()
                .filter(CommunityPlacementOperationValue.class::isInstance)
                .map(CommunityPlacementOperationValue.class::cast)
                .filter(CommunityPlacementOperationValue::active)
                .map(CommunityPlacementOperationValue::targetNode)
                .forEach(nodes::add);

        return nodes.size() < fleetLimit.getAsInt();
    }

    private static Map<NodeId, AetherValue.NodePlacementValue> communityMembers(Map<AetherKey, AetherValue> snapshot,
                                                                                String community) {
        return snapshot.entrySet()
                       .stream()
                       .filter(entry -> entry.getKey() instanceof AetherKey.ActivationDirectiveKey
                                        && entry.getValue() instanceof AetherValue.ActivationDirectiveValue directive
                                        && directive.role()
                                                    .equals(AetherValue.ActivationDirectiveValue.WORKER)
                                        && directive.communityId()
                                                    .equals(community))
                       .map(entry -> ((AetherKey.ActivationDirectiveKey) entry.getKey()).nodeId())
                       .filter(node -> snapshot.get(new AetherKey.NodePlacementKey(node)) instanceof AetherValue.NodePlacementValue)
                       .collect(Collectors.toMap(Function.identity(),
                                                 node -> (AetherValue.NodePlacementValue) snapshot.get(new AetherKey.NodePlacementKey(node))));
    }

    private static List<NodeId> matching(Map<NodeId, AetherValue.NodePlacementValue> members,
                                         CommunityPlacement.Location location) {
        return members.entrySet()
                      .stream()
                      .filter(entry -> matches(entry.getValue(),
                                               location))
                      .map(Map.Entry::getKey)
                      .toList();
    }

    private static boolean matches(AetherValue.NodePlacementValue observed, CommunityPlacement.Location desired) {
        return observed.sourceName()
                       .equals(desired.source()) && desired.zone()
                                                           .fold(() -> true,
                                                                 zone -> observed.observedZone()
                                                                                 .filter(zone::equals)
                                                                                 .isPresent());
    }
}
