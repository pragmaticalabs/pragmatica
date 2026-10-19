// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.*;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// One capacity admission boundary for core replacement, legacy scaling and community movement.
/// Provider failures retain their reservations: absence of an acknowledgement is not absence of a VM.
public record CapacityControlledLifecycle(NodeLifecycleManager delegate,
                                          NodeId self,
                                          KVStore<AetherKey, AetherValue> store,
                                          Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                                          BooleanSupplier activeLeader,
                                          IntSupplier limit,
                                          AtomicBoolean initializing,
                                          Set<InventorySource> inventoried,
                                          AtomicBoolean inventoryReconciling,
                                          Set<String> activeSources,
                                          ConcurrentHashMap<String, AtomicInteger> inventoryCursors,
                                          ConcurrentHashMap<NodeId, SourceName> undispatched,
                                          ConcurrentHashMap<NodeId, DispatchStamp> dispatchStamps,
                                          ConcurrentHashMap<NodeId, AtomicInteger> dispatchMisses,
                                          ConcurrentHashMap<NodeId, Long> unmatchedSince,
                                          AtomicBoolean leading,
                                          AtomicLong leadershipGeneration,
                                          AtomicReference<OperatorWarningSink> warningSink,
                                          LongSupplier clock,
                                          TimeSpan ceiling) implements NodeLifecycleManager {
    private static final Logger log = LoggerFactory.getLogger(CapacityControlledLifecycle.class);

    public static CapacityControlledLifecycle capacityControlledLifecycle(NodeLifecycleManager delegate,
                                                                          NodeId self,
                                                                          KVStore<AetherKey, AetherValue> store,
                                                                          Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                                                                          BooleanSupplier activeLeader,
                                                                          IntSupplier limit) {
        return capacityControlledLifecycle(delegate,
                                           self,
                                           store,
                                           apply,
                                           activeLeader,
                                           limit,
                                           SourceProfile.DEFAULT_REPLACEMENT_CEILING);
    }

    /// `ceiling` bounds how long one admission, or one fleet inventory initialization, may hold its slot: the per-source default ceiling
    /// of a replacement, ten minutes. A hung provider call must not hold a source binding, or the initialization flag, for ever.
    static CapacityControlledLifecycle capacityControlledLifecycle(NodeLifecycleManager delegate,
                                                                   NodeId self,
                                                                   KVStore<AetherKey, AetherValue> store,
                                                                   Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                                                                   BooleanSupplier activeLeader,
                                                                   IntSupplier limit,
                                                                   TimeSpan ceiling) {
        return capacityControlledLifecycle(delegate,
                                           self,
                                           store,
                                           apply,
                                           activeLeader,
                                           limit,
                                           ceiling,
                                           System::currentTimeMillis);
    }

    /// `clock` (epoch milliseconds) times the grace an earlier-term allocation is given before it can be released.
    static CapacityControlledLifecycle capacityControlledLifecycle(NodeLifecycleManager delegate,
                                                                   NodeId self,
                                                                   KVStore<AetherKey, AetherValue> store,
                                                                   Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                                                                   BooleanSupplier activeLeader,
                                                                   IntSupplier limit,
                                                                   TimeSpan ceiling,
                                                                   LongSupplier clock) {
        return new CapacityControlledLifecycle(delegate,
                                               self,
                                               store,
                                               apply,
                                               activeLeader,
                                               limit,
                                               new AtomicBoolean(),
                                               ConcurrentHashMap.newKeySet(),
                                               new AtomicBoolean(),
                                               new HashSet<>(),
                                               new ConcurrentHashMap<>(),
                                               new ConcurrentHashMap<>(),
                                               new ConcurrentHashMap<>(),
                                               new ConcurrentHashMap<>(),
                                               new ConcurrentHashMap<>(),
                                               new AtomicBoolean(),
                                               new AtomicLong(),
                                               new AtomicReference<>(OperatorWarningSink.logOnly()),
                                               clock,
                                               ceiling);
    }

    public enum AdmissionFailure implements org.pragmatica.lang.Cause {
        CAPACITY_UNAVAILABLE,
        INVENTORY_UNAVAILABLE,
        NO_READY_PEERS,
        INACTIVE_AUTHORITY,
        INVALID_CONFIGURATION;
        @Override
        public String message() {
            return switch (this) {
                case CAPACITY_UNAVAILABLE -> "Capacity or source admission unavailable; no provider create was dispatched";
                case INVENTORY_UNAVAILABLE -> "Complete inventory is unavailable; no provider create was dispatched";
                case NO_READY_PEERS -> "No ready core seed peers; no provider create was dispatched";
                case INACTIVE_AUTHORITY -> "Active core authority is unavailable; no provider create was dispatched";
                case INVALID_CONFIGURATION -> "Provisioning configuration is invalid; no provider create was dispatched";
            };
        }
    }

    /// One continuing-inventory pass over the configured sources, single-flight: adopts what each source lists and confirms the absence of
    /// allocations it listed before, releasing their capacity. A pass that cannot list a source leaves that source's allocations as they are.
    @Override
    public Promise<Unit> reconcileInventory() {
        if (leader().isEmpty() || !inventoryReconciling.compareAndSet(false, true)) return Promise.unitPromise();

        return configuredInventory().fold(this::releaseInventoryPass);
    }

    /// The flag is released BEFORE the pass's result is delivered: `onResultRun` runs its action after the result is visible to waiters, so
    /// a caller that retries at once could still find the pass in progress.
    private Promise<Unit> releaseInventoryPass(Result<Unit> result) {
        inventoryReconciling.set(false);
        forgetResolvedDispatches();

        return result.async();
    }

    /// The per-node dispatch memory outlives nothing: a node whose reservation is no longer DISPATCHED has nothing left to examine.
    @Contract
    private void forgetResolvedDispatches() {
        var current = reservations();

        dispatchStamps.keySet().removeIf(node -> !isDispatched(current, node));
        unmatchedSince.keySet().removeIf(node -> !isDispatched(current, node));
        dispatchMisses.keySet().removeIf(node -> !isDispatched(current, node));
    }

    private static boolean isDispatched(Map<NodeId, CapacityReservationValue> reservations, NodeId node) {
        return Option.option(reservations.get(node))
                     .filter(value -> value.phase() == CapacityReservationPhase.DISPATCHED)
                     .isPresent();
    }

    private Promise<Unit> configuredInventory() {
        return store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class)
                    .fold(Promise::unitPromise,
                          config -> inventorySources(config).async()
                                                    .flatMap(this::reconcileSources));
    }

    private Promise<Unit> reconcileSources(List<InventorySource> sources) {
        return ReconciliationBatch.reconcile(sources,
                                             4,
                                             TimeSpan.timeSpan(30).seconds(),
                                             this::reconcileSource,
                                             (source, cause) -> log.warn("Capacity inventory for {} deferred: {}",
                                                                         source.source(),
                                                                         cause.message()));
    }

    /// The continuing pass lists the source under this instance's leadership and counts it inventoried (the same mark an admission's
    /// inventory sets): a new leader that has admitted nothing yet must still be able to tell which sources it has seen, or the
    /// allocations an earlier term left behind could never be examined.
    private Promise<Unit> reconcileSource(InventorySource inventory) {
        return SourceName.sourceName(inventory.source())
                         .async()
                         .flatMap(source -> delegate.sourceBinding(source)
                                                    .async()
                                                    .flatMap(binding -> listAndMark(inventory, source, binding).flatMap(instances -> confirmMissing(source,
                                                                                                                                                    binding,
                                                                                                                                                    instances))));
    }

    private Promise<Unit> confirmMissing(SourceName source, String binding, List<InstanceInfo> instances) {
        var observed = instances.stream()
                                .map(instance -> observedNode(instance, source))
                                .collect(Collectors.toUnmodifiableSet());
        var missing = reservations().entrySet()
                                  .stream()
                                  .filter(entry -> entry.getValue()
                                                        .sourceName()
                                                        .equals(source.value()))
                                  .filter(entry -> entry.getValue()
                                                        .sourceBinding()
                                                        .equals(binding))
                                  .filter(entry -> entry.getValue()
                                                        .phase() == CapacityReservationPhase.OBSERVED)
                                  .filter(entry -> !observed.contains(entry.getKey()))
                                  .map(Map.Entry::getKey)
                                  .sorted(Comparator.comparing(NodeId::id))
                                  .toList();

        return ReconciliationBatch.reconcile(nextMissing(binding, missing),
                                             4,
                                             TimeSpan.timeSpan(10).seconds(),
                                             node -> instancesForNode(node, source, binding).mapToUnit(),
                                             (node, cause) -> log.warn("Capacity absence confirmation for {} deferred: {}",
                                                                       node,
                                                                       cause.message()))
                                  .flatMap(_ -> confirmDispatches(source, binding, observed));
    }

    /// Consecutive provider answers that must show no instance before an earlier term's DISPATCHED allocation is released.
    static final int ABSENCE_CONFIRMATIONS = 3;

    /// A DISPATCHED allocation is an uncertain create. One this leader dispatched itself since it last gained leadership, and younger than
    /// the create ceiling, is in flight and is not touched. Any other (written before that, older than the ceiling, another node's, or from
    /// a restarted process) is examined once this instance has inventoried every committed source: its instance is adopted when a lookup
    /// shows it, and it is released only after [#ABSENCE_CONFIRMATIONS] consecutive misses AND a grace of the create ceiling since this
    /// leader first found it unmatched, so a slow cloud boot is never released mid-boot. The same bounded lanes as the OBSERVED absence
    /// confirmation.
    private Promise<Unit> confirmDispatches(SourceName source, String binding, Set<NodeId> observed) {
        return leader().filter(_ -> allSourcesInventoried())
                     .fold(Promise::unitPromise,
                           _ -> confirmEarlierTermDispatches(source,
                                                             binding,
                                                             staleDispatches(source, binding, observed)));
    }

    /// Fail-safe: a configuration that cannot be read, or none committed with an incomplete ledger, is not "every source inventoried".
    boolean allSourcesInventoried() {
        return unseenSources(configuration()).fold(_ -> false, List::isEmpty);
    }

    private List<NodeId> staleDispatches(SourceName source, String binding, Set<NodeId> observed) {
        var generation = leadershipGeneration.get();
        var now = clock.getAsLong();

        return reservations().entrySet()
                           .stream()
                           .filter(entry -> entry.getValue()
                                                 .sourceName()
                                                 .equals(source.value()))
                           .filter(entry -> entry.getValue()
                                                 .sourceBinding()
                                                 .equals(binding))
                           .filter(entry -> entry.getValue()
                                                 .phase() == CapacityReservationPhase.DISPATCHED)
                           .filter(entry -> !observed.contains(entry.getKey()))
                           .filter(entry -> isUnmatchedDispatch(entry.getKey(),
                                                                generation,
                                                                now))
                           .map(Map.Entry::getKey)
                           .sorted(Comparator.comparing(NodeId::id))
                           .toList();
    }

    /// When and under which leadership generation this process dispatched a create.
    public record DispatchStamp(long generation, long at) {}

    /// The leadership generation decides only whether a dispatch is this leadership's own; the create ceiling bounds EVERY dispatch. An
    /// allocation this process does not remember, one from an earlier generation, and an own one older than the ceiling (a provider call
    /// that never resolved, an uncertain failure, an interlude of another leader this instance never observed) all enter the absence
    /// confirmation.
    private boolean isUnmatchedDispatch(NodeId node, long generation, long now) {
        return Option.option(dispatchStamps.get(node)).fold(() -> true,
                                                            stamp -> stamp.generation() < generation || now - stamp.at() >= ceiling.millis());
    }

    private Promise<Unit> confirmEarlierTermDispatches(SourceName source, String binding, List<NodeId> stale) {
        return ReconciliationBatch.reconcile(nextMissing(binding + "#dispatched", stale),
                                             4,
                                             TimeSpan.timeSpan(10).seconds(),
                                             node -> confirmEarlierTermDispatch(node, source, binding),
                                             (node, cause) -> log.warn("Absence confirmation of earlier-term dispatch {} deferred: {}",
                                                                       node,
                                                                       cause.message()));
    }

    private Promise<Unit> confirmEarlierTermDispatch(NodeId node, SourceName source, String binding) {
        return delegate.instancesForNode(node, source, binding)
                       .flatMap(instances -> afterDispatchLookup(node, source, binding, instances));
    }

    private Promise<Unit> afterDispatchLookup(NodeId node,
                                              SourceName source,
                                              String binding,
                                              List<InstanceInfo> instances) {
        return instances.isEmpty()
               ? absentDispatch(node, source, binding)
               : seenDispatch(node, source, binding, instances);
    }

    /// A sighting restarts the count before the adoption is attempted, so "consecutive" holds even when the adoption commit is refused.
    private Promise<Unit> seenDispatch(NodeId node, SourceName source, String binding, List<InstanceInfo> instances) {
        dispatchMisses.remove(node);
        unmatchedSince.remove(node);

        return observeBound(instances, source, binding);
    }

    private Promise<Unit> absentDispatch(NodeId node, SourceName source, String binding) {
        var now = clock.getAsLong();
        var since = unmatchedSince.computeIfAbsent(node, _ -> now);
        var misses = dispatchMisses.computeIfAbsent(node, _ -> new AtomicInteger()).incrementAndGet();

        return misses >= ABSENCE_CONFIRMATIONS && now - since >= ceiling.millis()
               ? releaseAbsentDispatch(node, source, binding)
               : Promise.unitPromise();
    }

    /// Same release as a definitive provider refusal: the no-create evidence first, then the slot.
    private Promise<Unit> releaseAbsentDispatch(NodeId node, SourceName source, String binding) {
        return recordRefusal(node, source, binding).flatMap(_ -> releaseRefusal(node, source, binding))
                            .onSuccess(_ -> announceAbsentDispatch(node, source));
    }

    @Contract
    private void announceAbsentDispatch(NodeId node, SourceName source) {
        dispatchMisses.remove(node);
        unmatchedSince.remove(node);
        log.warn("Capacity allocation of {} on source {} released: no instance of its uncertain create was found",
                 node.id(),
                 source.value());
        OperatorWarnings.raise(log,
                               warningSink.get(),
                               OperatorWarningCode.CAPACITY_DISPATCH_RELEASED,
                               node.id(),
                               "The uncertain create of {} on source {} was not confirmed: no provider listing or lookup showed an instance in {} consecutive passes over the create ceiling, so its allocation was released",
                               node.id(),
                               source.value(),
                               ABSENCE_CONFIRMATIONS);
    }

    @Override
    public Unit setOperatorWarningSink(OperatorWarningSink sink) {
        warningSink.set(sink);

        return Unit.unit();
    }

    private List<NodeId> nextMissing(String binding, List<NodeId> missing) {
        if (missing.size() <= 32) return missing;

        var cursor = inventoryCursors.computeIfAbsent(binding, _ -> new AtomicInteger());
        var start = Math.floorMod(cursor.getAndAdd(32), missing.size());

        return IntStream.range(0, 32)
                        .mapToObj(offset -> missing.get((start + offset) % missing.size()))
                        .toList();
    }

    @Override
    public Map<NodeId, CapacityReservationPhase> allocatedNodes(String role) {
        return reservations().entrySet()
                           .stream()
                           .filter(entry -> entry.getValue()
                                                 .intendedRole()
                                                 .equalsIgnoreCase(role))
                           .filter(entry -> entry.getValue()
                                                 .phase() != CapacityReservationPhase.RELEASED)
                           .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                 entry -> entry.getValue()
                                                                               .phase()));
    }

    @Override
    public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
        return delegate.sourceBinding(spec.context().sourceName())
                       .async()
                       .flatMap(binding -> provisionBound(spec, binding));
    }

    private Promise<InstanceInfo> provisionBound(ProvisionSpec spec, String binding) {
        return spec.context()
                   .nodeId()
                   .fold(() -> Causes.cause("Capacity reservation requires a stable node identity").promise(),
                         raw -> NodeId.nodeId(raw)
                                      .async()
                                      .flatMap(node -> existingAttempt(node).flatMap(_ -> admissionInventory())
                                                                      .flatMap(configuration -> admitSource(node,
                                                                                                            spec,
                                                                                                            binding,
                                                                                                            configuration))));
    }

    /// One create per source binding at a time, at most four bindings in a process: a busy source defers before it reserves capacity or
    /// reaches the provider. The slot is taken after the inventory is known complete, so a caller that arrives during an inventory
    /// initialization still receives its typed transient cause ([FleetInventoryInitializing]).
    private Promise<InstanceInfo> admitSource(NodeId node,
                                              ProvisionSpec spec,
                                              String binding,
                                              AetherValue.ClusterConfigValue inventoriedUnder) {
        if (!acquireSource(binding)) {
            return AdmissionFailure.CAPACITY_UNAVAILABLE.promise();
        }

        return reserveBound(node,
                            spec.context().sourceName(),
                            binding,
                            spec.context().role(),
                            inventoriedUnder).flatMap(accepted -> accepted
                                                                  ? dispatch(node, spec)
                                                                  : AdmissionFailure.CAPACITY_UNAVAILABLE.promise())
                           .timeout(ceiling)
                           .fold(result -> releaseSourceBinding(binding, result));
    }

    private synchronized boolean acquireSource(String binding) {
        return activeSources.size() < 4 && activeSources.add(binding);
    }

    /// As [#releaseInventoryPass]: the binding is free before the create's outcome reaches the caller.
    private synchronized Promise<InstanceInfo> releaseSourceBinding(String binding, Result<InstanceInfo> result) {
        activeSources.remove(binding);

        return result.async();
    }

    private Promise<Unit> existingAttempt(NodeId node) {
        return store.get(new AetherKey.CapacityReservationKey(node))
                    .isPresent()
               ? Causes.cause("Existing capacity reservation has an unresolved or already dispatched provider operation").promise()
               : Promise.unitPromise();
    }

    private Promise<InstanceInfo> dispatch(NodeId node, ProvisionSpec spec) {
        return leader().fold(() -> abandonDispatch(node,
                                                   spec.context().sourceName()),
                             current -> dispatchUnder(node, spec, current));
    }

    /// The leadership generation is remembered per node so that this instance can tell an allocation it dispatched itself since it last
    /// gained leadership, in flight, from one an earlier leadership left behind. Process-local on purpose: an instance that does not know a
    /// DISPATCHED allocation treats it as earlier, which is what a restarted or newly elected leader is. The generation advances only when
    /// this node GAINS leadership; a same-leader re-commit (new view sequence, no lost leadership) does not advance it.
    private Promise<InstanceInfo> dispatchUnder(NodeId node, ProvisionSpec spec, LeaderValue current) {
        dispatchStamps.put(node,
                           new DispatchStamp(leadershipGeneration.get(), clock.getAsLong()));

        return store.getTyped(new AetherKey.CapacityReservationKey(node),
                              CapacityReservationValue.class)
                    .toResult(Causes.cause("Missing committed capacity binding before create"))
                    .async()
                    .flatMap(reservation -> dispatchBound(node,
                                                          spec,
                                                          reservation.sourceBinding()));
    }

    /// The reservation is committed and the provider was never asked: leadership moved between the two, so this create provably did not
    /// happen and the DISPATCHED reservation must not stay an uncertain allocation. Releasing it needs the leader that was just lost, so the
    /// node is remembered and [#reconcileRefusals] releases it through the refusal path once this instance leads again. A new leader of
    /// another instance cannot know the create never happened and keeps counting it (D1): that is the cost of the lost term, not a leak.
    private Promise<InstanceInfo> abandonDispatch(NodeId node, SourceName source) {
        undispatched.put(node, source);

        return Causes.cause("Core leadership changed before provider dispatch").promise();
    }

    private Promise<InstanceInfo> dispatchBound(NodeId node, ProvisionSpec spec, String binding) {
        undispatched.remove(node);

        return delegate.provisionNode(spec, binding)
                       .fold(result -> result.fold(cause -> {
                                                       if (cause instanceof EnvironmentError.CapacityUnavailable || cause instanceof EnvironmentError.NodeCapExceeded) {
                                                           return recordRefusal(node,
                                                                                spec.context().sourceName(),
                                                                                binding).flatMap(_ -> releaseRefusal(node,
                                                                                                                     spec.context()
                                                                                                                         .sourceName(),
                                                                                                                     binding).fold(_ -> cause.promise()));
                                                       }

                                                       return cause.promise();
                                                   },
                                                   instance -> observeBound(List.of(instance),
                                                                            spec.context().sourceName(),
                                                                            binding).map(_ -> instance)));
    }

    private Promise<Unit> releaseRefusal(NodeId node, SourceName source, String binding) {
        return hasActivePlacement(node)
               ? Promise.unitPromise()
               : release(node, source, binding, CapacityReservationPhase.RELEASED);
    }

    private boolean hasActivePlacement(NodeId node) {
        Map<?, ?> snapshot = store.snapshot();

        return snapshot.values()
                       .stream()
                       .filter(AetherValue.CommunityPlacementOperationValue.class::isInstance)
                       .map(AetherValue.CommunityPlacementOperationValue.class::cast)
                       .anyMatch(operation -> operation.active() && operation.targetNode()
                                                                             .equals(node));
    }

    /// Persist no-create evidence independently of the contended fleet counter.
    /// FER: a failed counter release retains RELEASED for the next inventory pass.
    private Promise<Unit> recordRefusal(NodeId node, SourceName source, String binding) {
        var key = new AetherKey.CapacityReservationKey(node);

        return store.getTyped(key, CapacityReservationValue.class)
                    .filter(value -> value.phase() == CapacityReservationPhase.DISPATCHED
                                     && value.sourceName()
                                             .equals(source.value())
                                     && value.sourceBinding()
                                             .equals(binding))
                    .fold(() -> HierarchyStateWriter.Refusal.CONFLICT.promise(),
                          before -> persistRefusal(key, before));
    }

    private Promise<Unit> persistRefusal(AetherKey.CapacityReservationKey key, CapacityReservationValue before) {
        return leader().fold(() -> HierarchyStateWriter.Refusal.CONFLICT.promise(),
                             current -> {
                                 var id = UUID.randomUUID().toString();
                                 var after = new CapacityReservationValue(before.sourceName(),
                                                                          before.sourceBinding(),
                                                                          before.intendedRole(),
                                                                          CapacityReservationPhase.RELEASED);
                                 var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                               Option.some(before),
                                                                                               Option.some(after));
                                 var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                       id,
                                                                                                       current,
                                                                                                       List.of(),
                                                                                                       List.of(mutation));

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

    @Override
    public Promise<Unit> reconcileRefusals() {
        if (leader().isEmpty()) return Promise.unitPromise();

        return refuseUndispatched().flatMap(_ -> releaseRefusals());
    }

    /// Records the no-create evidence of every dispatch abandoned before the provider call (see [#abandonDispatch]). An entry leaves the
    /// memory once its reservation is no longer the DISPATCHED one it was made for; a refusal that conflicts stays for the next pass.
    private Promise<Unit> refuseUndispatched() {
        return ReconciliationBatch.reconcile(List.copyOf(undispatched.entrySet()),
                                             4,
                                             TimeSpan.timeSpan(10).seconds(),
                                             entry -> refuseUndispatched(entry.getKey(), entry.getValue()),
                                             (entry, cause) -> log.warn("Refusal of abandoned dispatch for {} deferred: {}",
                                                                        entry.getKey(),
                                                                        cause.message()));
    }

    private Promise<Unit> refuseUndispatched(NodeId node, SourceName source) {
        return store.getTyped(new AetherKey.CapacityReservationKey(node),
                              CapacityReservationValue.class)
                    .filter(reservation -> reservation.phase() == CapacityReservationPhase.DISPATCHED)
                    .fold(Promise::unitPromise,
                          reservation -> recordRefusal(node,
                                                       source,
                                                       reservation.sourceBinding()))
                    .onSuccess(_ -> undispatched.remove(node));
    }

    private Promise<Unit> releaseRefusals() {
        return ReconciliationBatch.reconcile(reservations().entrySet()
                                                         .stream()
                                                         .filter(entry -> entry.getValue()
                                                                               .phase() == CapacityReservationPhase.RELEASED && !hasActivePlacement(entry.getKey()))
                                                         .toList(),
                                             4,
                                             org.pragmatica.lang.io.TimeSpan.timeSpan(10).seconds(),
                                             entry -> SourceName.sourceName(entry.getValue().sourceName())
                                                                .async()
                                                                .flatMap(source -> release(entry.getKey(),
                                                                                           source,
                                                                                           entry.getValue()
                                                                                                .sourceBinding(),
                                                                                           CapacityReservationPhase.RELEASED)),
                                             (entry, cause) -> org.slf4j.LoggerFactory.getLogger(CapacityControlledLifecycle.class)
                                                                                      .warn("Refused capacity release for {} deferred: {}",
                                                                                            entry.getKey(),
                                                                                            cause.message()));
    }

    private Map<NodeId, CapacityReservationValue> reservations() {
        var result = new java.util.HashMap<NodeId, CapacityReservationValue>();
        Map<?, ?> snapshot = store.snapshot();

        snapshot.forEach((key, value) -> {
            if (key instanceof AetherKey.CapacityReservationKey reservation && value instanceof CapacityReservationValue state) {
                result.put(reservation.nodeId(), state);
            }
        });

        return Map.copyOf(result);
    }

    private Option<LeaderValue> leader() {
        return observeLeadership(store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                                      .filter(value -> activeLeader.getAsBoolean() && value.leader()
                                                                                           .equals(self)));
    }

    /// Counts lose -> gain edges as the instance observes them (every reconcile and dispatch asks `leader()`). A gain also starts the
    /// absence counts afresh: they are "this leader's". [limit: an interlude of another leader that this instance never observed,
    /// with no call to it in between, reads as continuous leadership.]
    private Option<LeaderValue> observeLeadership(Option<LeaderValue> current) {
        if (leading.getAndSet(current.isPresent()) != current.isPresent() && current.isPresent()) {
            leadershipGeneration.incrementAndGet();
            dispatchMisses.clear();
            unmatchedSince.clear();
        }

        return current;
    }

    private Option<CapacityLedgerValue> ledger() {
        return store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class);
    }

    /// Admission needs a committed configuration and every source it names inventoried: the returned configuration is the one the
    /// inventory is valid for, and [#reserveBound] admits only while it is still the committed one.
    private Promise<AetherValue.ClusterConfigValue> admissionInventory() {
        return configuration().fold(() -> AdmissionFailure.INVENTORY_UNAVAILABLE.promise(), this::inventoryUnder);
    }

    private Promise<AetherValue.ClusterConfigValue> inventoryUnder(AetherValue.ClusterConfigValue configuration) {
        return ensureInventory(Option.some(configuration)).onFailure(cause -> log.warn("Capacity admission awaits complete inventory: {}",
                                                                                       cause.message()))
                              .fold(result -> result.fold(this::inventoryRefusal, Promise::success))
                              .map(_ -> configuration);
    }

    /// Another caller's initialization in progress (#2098) stays the typed transient cause the node replacement reconciler waits on;
    /// every other inventory failure is the pre-dispatch refusal.
    private Promise<Unit> inventoryRefusal(Cause cause) {
        return cause instanceof FleetInventoryInitializing
               ? cause.promise()
               : AdmissionFailure.INVENTORY_UNAVAILABLE.promise();
    }

    private Option<AetherValue.ClusterConfigValue> configuration() {
        return store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class);
    }

    private boolean inventoryComplete() {
        return ledger().filter(CapacityLedgerValue::inventoryComplete)
                     .isPresent();
    }

    /// Admission needs every source the committed configuration names to have been inventoried by this
    /// instance, not only a ledger that says "complete" (#2142). The ledger bit is set once, under the
    /// bootstrap seed, when only the implicit local source existed; an operator configuration committed
    /// later names sources whose pre-existing labelled instances were never listed, and admitting on them
    /// would under-count the fleet and over-provision.
    ///
    /// The inventoried set is deliberately instance-local rather than ledger state. A new leader starts
    /// with an empty set and re-inventories every committed source once; that is safe because
    /// [#observeBound] skips reservations already OBSERVED and counts only new ones, so the cost of
    /// losing the set is one extra provider listing per source, never a double count.
    private Promise<Unit> ensureInventory() {
        return ensureInventory(configuration());
    }

    private Promise<Unit> ensureInventory(Option<AetherValue.ClusterConfigValue> configuration) {
        return unseenSources(configuration).async()
                            .flatMap(this::inventoryUnseen);
    }

    /// Termination only needs a ledger that is inventoried at all (the pre-#2142 condition). It must not depend on
    /// listing a source it has nothing to do with: a newly committed source that cannot be listed would otherwise
    /// block a cost-saving termination of a node that belongs to another source.
    private Promise<Unit> ensureTerminationInventory() {
        return inventoryComplete()
               ? Promise.unitPromise()
               : ensureInventory();
    }

    private Result<List<InventorySource>> unseenSources(Option<AetherValue.ClusterConfigValue> configuration) {
        return configuration.fold(this::unseenWithoutConfig, config -> inventorySources(config).map(this::unseen));
    }

    private Result<List<InventorySource>> unseenWithoutConfig() {
        return inventoryComplete()
               ? Result.success(List.of())
               : Causes.cause("Committed source configuration required for fleet inventory").result();
    }

    /// A source that left the configuration is forgotten here (#2142): instances created on it while it was
    /// absent are not counted, so when it is committed again it must be listed again, not skipped as "seen".
    private List<InventorySource> unseen(List<InventorySource> sources) {
        inventoried.retainAll(sources);

        return sources.stream()
                      .filter(source -> !inventoried.contains(source))
                      .toList();
    }

    private Promise<Unit> inventoryUnseen(List<InventorySource> unseen) {
        if (unseen.isEmpty() && inventoryComplete()) {
            return Promise.unitPromise();
        }

        if (!initializing.compareAndSet(false, true)) {
            return new FleetInventoryInitializing().promise();
        }

        return inventory(unseen).timeout(ceiling)
                        .fold(this::releaseInitialization);
    }

    /// As [#releaseInventoryPass]: the initialization flag is released before the outcome is delivered.
    private Promise<Unit> releaseInitialization(Result<Unit> result) {
        initializing.set(false);

        return result.async();
    }

    public record InventorySource(String source, String cluster) {}

    /// The sources a fleet inventory lists. A committed configuration names them (SSH sources are not
    /// provisioned). The bootstrap seed declares none: its single implicit source is the local default it
    /// provisions through, which [SourceComputeRegistry] resolves only to an explicitly local provider.
    private static org.pragmatica.lang.Result<List<InventorySource>> inventorySources(AetherValue.ClusterConfigValue config) {
        return config.tomlContent()
                     .fold(() -> org.pragmatica.lang.Result.success(List.of(new InventorySource(SourceName.DEFAULT.value(),
                                                                                                config.clusterName()))),
                           toml -> ClusterBootstrapConfigParser.parse(toml).map(parsed -> parsed.sources()
                                                                                                .values()
                                                                                                .stream()
                                                                                                .filter(value -> value.type() != SourceType.SSH)
                                                                                                .sorted(java.util.Comparator.comparing(value -> value.name()
                                                                                                                                                     .value()))
                                                                                                .map(value -> new InventorySource(value.name()
                                                                                                                                       .value(),
                                                                                                                                  parsed.cluster()
                                                                                                                                        .name()
                                                                                                                                        .value()))
                                                                                                .toList()));
    }

    private Promise<Unit> inventory(List<InventorySource> sources) {
        var pass = Promise.unitPromise();

        for (var source : sources) {
            pass = pass.flatMap(_ -> inventorySource(source));
        }

        return pass.flatMap(_ -> markInventoryComplete());
    }

    /// A source counts as inventoried only when its instances were OBSERVED into the ledger under this
    /// instance's leadership (#2142). The public listing hands instances back unobserved when this instance
    /// is not the active leader; marking the source then would let admission skip it for good with nothing
    /// counted. Here a missing leader fails the inventory instead, and the observation's own commit is
    /// leader-fenced, so a term lost mid-listing fails it too.
    private Promise<Unit> inventorySource(InventorySource source) {
        return SourceName.sourceName(source.source())
                         .async()
                         .flatMap(name -> inventoryBound(source, name));
    }

    private Promise<Unit> inventoryBound(InventorySource source, SourceName name) {
        return delegate.sourceBinding(name)
                       .async()
                       .flatMap(binding -> listAndMark(source, name, binding))
                       .mapToUnit();
    }

    private Promise<List<InstanceInfo>> listAndMark(InventorySource source, SourceName name, String binding) {
        return delegate.listInstances(Map.of("aether-source",
                                             source.source(),
                                             "aether-cluster",
                                             source.cluster()),
                                      name,
                                      binding)
                       .flatMap(instances -> observeAsLeader(instances, name, binding))
                       .onSuccess(_ -> inventoried.add(source));
    }

    private Promise<List<InstanceInfo>> observeAsLeader(List<InstanceInfo> instances,
                                                        SourceName source,
                                                        String binding) {
        return leader().isEmpty()
               ? Causes.cause("Fleet inventory requires active core leadership").promise()
               : observeAll(instances, source, binding);
    }

    private Promise<List<InstanceInfo>> observeAll(List<InstanceInfo> instances, SourceName source, String binding) {
        var pass = Promise.unitPromise();

        for (int offset = 0; offset < instances.size(); offset += 128) {
            var batch = List.copyOf(instances.subList(offset,
                                                      Math.min(offset + 128, instances.size())));

            pass = pass.flatMap(_ -> observeBound(batch, source, binding));
        }

        return pass.map(_ -> instances);
    }

    private Promise<Unit> markInventoryComplete() {
        var current = ledger();
        var next = current.map(value -> new CapacityLedgerValue(value.allocated(),
                                                                value.version() + 1,
                                                                true))
                          .or(new CapacityLedgerValue(0, 1, true));

        return mutate(current, next, List.of()).flatMap(accepted -> accepted
                                                                    ? Promise.unitPromise()
                                                                    : Causes.cause("Fleet inventory commit lost core authority or concurrent reservation").promise());
    }

    /// Reserves only under the configuration the admission inventoried for: the commit carries a read witness of it, so a configuration
    /// committed since (while the inventory was being taken, or before the commit lands) rejects the reservation, and the next admission
    /// inventories again.
    private Promise<Boolean> reserveBound(NodeId node,
                                          SourceName source,
                                          String binding,
                                          String intendedRole,
                                          AetherValue.ClusterConfigValue inventoriedUnder) {
        var key = new AetherKey.CapacityReservationKey(node);
        var current = ledger();
        var configuration = Option.some(inventoriedUnder);

        if (store.get(key).isPresent() || current.filter(value -> value.inventoryComplete() && value.allocated() < limit.getAsInt())
                                                 .isEmpty()) {
            return Promise.success(false);
        }

        return current.fold(() -> Promise.success(false),
                            value -> mutate(current,
                                            new CapacityLedgerValue(value.allocated() + 1, value.version() + 1, true),
                                            List.of(new KVCommand.ReadWitness<AetherKey>(AetherKey.ClusterConfigKey.CURRENT,
                                                                                         configuration.map(config -> (Object) config))),
                                            List.of(new KVCommand.Mutation<>(key,
                                                                             Option.none(),
                                                                             Option.some(new CapacityReservationValue(source.value(),
                                                                                                                      binding,
                                                                                                                      intendedRole.toLowerCase(java.util.Locale.ROOT),
                                                                                                                      CapacityReservationPhase.DISPATCHED))))));
    }

    private Promise<Unit> observeBound(List<InstanceInfo> instances, SourceName source, String binding) {
        var current = ledger();
        var before = current.or(new CapacityLedgerValue(0, 0, false));
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();
        int additions = 0;

        for (var instance : instances) {
            var node = observedNode(instance, source);
            var key = new AetherKey.CapacityReservationKey(node);
            var existing = store.getTyped(key, CapacityReservationValue.class);

            if (existing.filter(value -> !value.sourceName()
                                               .equals(source.value()) || !value.sourceBinding()
                                                                                .equals(binding))
                        .isPresent()) {
                return Causes.cause("Provider node identity is duplicated across capacity sources").promise();
            }

            if (existing.filter(value -> value.phase() == CapacityReservationPhase.OBSERVED).isPresent()) {
                continue;
            }

            if (existing.isEmpty()) {
                additions++;
            }

            mutations.add(new KVCommand.Mutation<>(key,
                                                   existing.map(value -> value),
                                                   Option.some(new CapacityReservationValue(source.value(),
                                                                                            binding,
                                                                                            existing.map(CapacityReservationValue::intendedRole)
                                                                                                    .or(""),
                                                                                            CapacityReservationPhase.OBSERVED))));
        }

        if (mutations.isEmpty()) {
            return Promise.unitPromise();
        }

        return mutate(current,
                      new CapacityLedgerValue(before.allocated() + additions,
                                              before.version() + 1,
                                              before.inventoryComplete()),
                      mutations).flatMap(accepted -> accepted
                                                     ? Promise.unitPromise()
                                                     : Causes.cause("Fleet observation conflicted; retry inventory").promise());
    }

    private static NodeId observedNode(InstanceInfo instance, SourceName source) {
        return instance.nodeId()
                       .map(NodeId::new)
                       .or(() -> new NodeId("unlabelled-" + UUID.nameUUIDFromBytes((source.value()
                                                                                   + ":" + instance.id()
                                                                                                   .value()).getBytes(StandardCharsets.UTF_8))));
    }

    private Promise<Boolean> mutate(Option<CapacityLedgerValue> before,
                                    CapacityLedgerValue after,
                                    List<KVCommand.Mutation<AetherKey, AetherValue>> changes) {
        return mutate(before, after, List.of(), changes);
    }

    private Promise<Boolean> mutate(Option<CapacityLedgerValue> before,
                                    CapacityLedgerValue after,
                                    List<KVCommand.ReadWitness<AetherKey>> guards,
                                    List<KVCommand.Mutation<AetherKey, AetherValue>> changes) {
        return leader().fold(() -> Promise.success(false),
                             currentLeader -> {
                                 var mutations = new ArrayList<>(changes);

                                 mutations.add(new KVCommand.Mutation<AetherKey, AetherValue>(AetherKey.CapacityLedgerKey.INSTANCE,
                                                                                              before.map(value -> value),
                                                                                              Option.some(after)));
                                 var id = UUID.randomUUID().toString();
                                 var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(AetherKey.CapacityLedgerKey.INSTANCE,
                                                                                                       id,
                                                                                                       currentLeader,
                                                                                                       guards,
                                                                                                       mutations);

                                 return apply.apply(List.of(command))
                                             .map(results -> results.stream()
                                                                    .filter(KVCommand.TransactionResult.class::isInstance)
                                                                    .map(KVCommand.TransactionResult.class::cast)
                                                                    .anyMatch(result -> result.transactionId()
                                                                                              .equals(id) && result.accepted()));
                             });
    }

    private Promise<Unit> releaseConfirmed(NodeId node, SourceName source, String binding) {
        return release(node, source, binding, CapacityReservationPhase.OBSERVED);
    }

    private Promise<Unit> release(NodeId node,
                                  SourceName source,
                                  String binding,
                                  CapacityReservationPhase expectedPhase) {
        var key = new AetherKey.CapacityReservationKey(node);
        var reservation = store.getTyped(key, CapacityReservationValue.class)
                               .filter(value -> value.sourceName()
                                                     .equals(source.value())
                                                && value.sourceBinding()
                                                        .equals(binding)
                                                && value.phase() == expectedPhase);

        return reservation.fold(Promise::unitPromise,
                                existing -> ledger().fold(Promise::unitPromise,
                                                          current -> current.allocated() <= 0
                                                                     ? Causes.cause("Capacity ledger is inconsistent with its reservation").promise()
                                                                     : mutate(Option.some(current),
                                                                              new CapacityLedgerValue(current.allocated() - 1,
                                                                                                      current.version() + 1,
                                                                                                      current.inventoryComplete()),
                                                                              releaseMutations(node, key, existing)).flatMap(accepted -> accepted
                                                                                                                                         ? Promise.unitPromise()
                                                                                                                                         : Causes.cause("Capacity release conflicted; retry absence confirmation").promise())));
    }

    private List<KVCommand.Mutation<AetherKey, AetherValue>> releaseMutations(NodeId node,
                                                                              AetherKey.CapacityReservationKey key,
                                                                              CapacityReservationValue existing) {
        var changes = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        changes.add(new KVCommand.Mutation<>(key, Option.some(existing), Option.none()));
        if (existing.phase() != CapacityReservationPhase.RELEASED) {
            var placementKey = new AetherKey.NodePlacementKey(node);

            store.get(placementKey)
                 .onPresent(value -> changes.add(new KVCommand.Mutation<>(placementKey,
                                                                          Option.some(value),
                                                                          Option.none())));
        }

        return changes;
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter) {
        return Option.option(filter.get("aether-source"))
                     .orElse(() -> Option.option(filter.get("aether.source")))
                     .fold(() -> delegate.listInstances(filter),
                           source -> SourceName.sourceName(source)
                                               .async()
                                               .flatMap(name -> delegate.sourceBinding(name)
                                                                        .async()
                                                                        .flatMap(binding -> listInstances(filter,
                                                                                                          name,
                                                                                                          binding))));
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter, SourceName source, String binding) {
        return delegate.listInstances(filter, source, binding)
                       .flatMap(instances -> observeIfLeader(instances, source, binding));
    }

    private Promise<List<InstanceInfo>> observeIfLeader(List<InstanceInfo> instances,
                                                        SourceName source,
                                                        String binding) {
        return leader().isEmpty()
               ? Promise.success(instances)
               : observeAll(instances, source, binding);
    }

    @Override
    public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source) {
        var reservation = store.getTyped(new AetherKey.CapacityReservationKey(node), CapacityReservationValue.class);

        return reservation.fold(() -> delegate.sourceBinding(source),
                                value -> org.pragmatica.lang.Result.success(value.sourceBinding()))
                          .async()
                          .flatMap(binding -> delegate.instancesForNode(node, source, binding)
                                                      .flatMap(instances -> {
                                                                   if (leader().isEmpty()) {
                                                                       return Promise.success(instances);
                                                                   }

                                                                   return instances.isEmpty()
                                                                          ? releaseConfirmed(node, source, binding).map(_ -> instances)
                                                                          : observeBound(instances, source, binding).map(_ -> instances);
                                                               }));
    }

    @Override
    public Promise<List<InstanceInfo>> readInstancesForNode(NodeId node, SourceName source) {
        return reservationBinding(node, source).async()
                                 .flatMap(binding -> delegate.instancesForNode(node, source, binding));
    }

    @Override
    public Promise<Unit> releaseAfterOperatorClear(NodeId node, SourceName source) {
        return leader().isEmpty()
               ? Promise.unitPromise()
               : reservationBinding(node, source).async()
                                   .flatMap(binding -> releaseConfirmed(node, source, binding));
    }

    private org.pragmatica.lang.Result<String> reservationBinding(NodeId node, SourceName source) {
        return store.getTyped(new AetherKey.CapacityReservationKey(node),
                              CapacityReservationValue.class)
                    .fold(() -> delegate.sourceBinding(source),
                          value -> org.pragmatica.lang.Result.success(value.sourceBinding()));
    }

    @Override
    public Promise<org.pragmatica.aether.environment.InstanceInfo> instanceStatus(org.pragmatica.aether.environment.InstanceId instanceId,
                                                                                  SourceName source) {
        return delegate.instanceStatus(instanceId, source);
    }

    @Override
    public Promise<List<InstanceInfo>> instancesForNode(NodeId node) {
        return source(node).fold(() -> delegate.instancesForNode(node), value -> instancesForNode(node, value));
    }

    private Option<SourceName> source(NodeId node) {
        return store.getTyped(new AetherKey.CapacityReservationKey(node),
                              CapacityReservationValue.class)
                    .map(value -> SourceName.sourceNameOrDefault(value.sourceName()));
    }

    /// The binding an EXTERNAL admission's reservation carries when the fleet ledger could not count it.
    public static final String UNCOUNTED_BINDING = "external-uncounted";

    /// A reservation written by an EXTERNAL admission has no provider binding (`""`, or [#UNCOUNTED_BINDING]): its node was started by
    /// an operator, so there is no provider instance of ours to terminate, and the registry would refuse either binding anyway.
    public static boolean isExternalBinding(String binding) {
        return binding.isEmpty() || UNCOUNTED_BINDING.equals(binding);
    }

    @Override
    public boolean externalNode(NodeId node) {
        return store.getTyped(new AetherKey.CapacityReservationKey(node),
                              CapacityReservationValue.class)
                    .filter(value -> isExternalBinding(value.sourceBinding()))
                    .isPresent();
    }

    @Override
    public Option<SourceName> sourceOf(NodeId node) {
        return source(node).orElse(() -> delegate.sourceOf(node));
    }

    private static final int READMISSION_ATTEMPTS = 3;

    @Override
    public Promise<Unit> readmitExternal(NodeId node) {
        var key = new AetherKey.CapacityReservationKey(node);

        return store.getTyped(key, CapacityReservationValue.class)
                    .fold(() -> admitReleased(node, key),
                          reservation -> isExternalBinding(reservation.sourceBinding())
                                         ? restamp(node, reservation, READMISSION_ATTEMPTS)
                                         : Promise.unitPromise());
    }

    /// The node joined and has NO reservation. A node retired by a replacement is SUPERSEDED (its tombstone): it is refused. When a release deleted an EXTERNAL reservation
    /// (the marker remembers it, whoever released it) the node is admitted again as a new admission, never left a member without a reservation and a slot. Anything
    /// else - no marker, or a marker with neither - is reported to the manager ([NoReservationOnJoin]); it is never silently left alone.
    private Promise<Unit> admitReleased(NodeId node, AetherKey.CapacityReservationKey key) {
        return admission(node).fold(() -> new NoReservationOnJoin(node.id()).<Unit> promise(),
                                    marker -> marker.supersededBy()
                                                    .fold(() -> marker.released()
                                                                      .fold(() -> new NoReservationOnJoin(node.id()).<Unit> promise(),
                                                                            released -> admitAgain(node, key, released)),
                                                          replacement -> new RetiredIdentityRejoined(node.id(),
                                                                                                     replacement).<Unit> promise()));
    }

    @Override
    public Promise<Unit> supersedeAdmission(NodeId node, NodeId replacement) {
        var key = new AetherKey.CapacityAdmissionKey(node);
        var before = marker(node);
        var admissions = before.map(AetherValue.CapacityAdmissionValue.class::cast)
                               .map(AetherValue.CapacityAdmissionValue::admissions)
                               .or(0L);
        var tombstone = new AetherValue.CapacityAdmissionValue(admissions,
                                                               Option.none(),
                                                               Option.some(replacement.id()));

        return transact(key,
                        List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key, before, Option.some(tombstone)))).flatMap(done -> done
                                                                                                                                      ? Promise.unitPromise()
                                                                                                                                      : Causes.cause("The retirement tombstone of " + node.id()
                                                                                                                                                    + " conflicted").promise());
    }

    @Override
    public Promise<Unit> forgetAdmission(NodeId node) {
        var key = new AetherKey.CapacityAdmissionKey(node);

        return marker(node).fold(Promise::unitPromise,
                                 present -> transact(key,
                                                     List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                                            Option.some(present),
                                                                                                            Option.none()))).flatMap(done -> done
                                                                                                                                             ? Promise.unitPromise()
                                                                                                                                             : Causes.cause("The admission marker of " + node.id()
                                                                                                                                                           + " changed while it was being deleted").promise()));
    }

    private Option<AetherValue.CapacityAdmissionValue> admission(NodeId node) {
        return store.getTyped(new AetherKey.CapacityAdmissionKey(node), AetherValue.CapacityAdmissionValue.class);
    }

    private Option<AetherValue> marker(NodeId node) {
        return store.getTyped(new AetherKey.CapacityAdmissionKey(node),
                              AetherValue.CapacityAdmissionValue.class)
                    .map(value -> (AetherValue) value);
    }

    private static KVCommand.Mutation<AetherKey, AetherValue> bumpMarker(NodeId node, Option<AetherValue> before) {
        var next = before.map(AetherValue.CapacityAdmissionValue.class::cast)
                         .map(AetherValue.CapacityAdmissionValue::admissions)
                         .or(0L) + 1;

        return new KVCommand.Mutation<>(new AetherKey.CapacityAdmissionKey(node),
                                        before,
                                        Option.some(new AetherValue.CapacityAdmissionValue(next,
                                                                                           Option.none(),
                                                                                           Option.none())));
    }

    /// The marker as the release leaves it: the same admission count, remembering the reservation it deleted.
    private static KVCommand.Mutation<AetherKey, AetherValue> rememberReleased(NodeId node,
                                                                               Option<AetherValue> before,
                                                                               CapacityReservationValue released) {
        var admissions = before.map(AetherValue.CapacityAdmissionValue.class::cast)
                               .map(AetherValue.CapacityAdmissionValue::admissions)
                               .or(0L);

        return new KVCommand.Mutation<>(new AetherKey.CapacityAdmissionKey(node),
                                        before,
                                        Option.some(new AetherValue.CapacityAdmissionValue(admissions,
                                                                                           Option.some(released))));
    }

    /// The node joined while its EXTERNAL reservation `reservation` is present: bump the admission marker, guarded by a read witness that the reservation is
    /// still exactly what was read. A release that read the marker earlier then fails; a release that committed first removed the reservation, which
    /// fails THIS transaction, and the node is then re-admitted as new ([#admitAgain]).
    private Promise<Unit> restamp(NodeId node, CapacityReservationValue reservation, int attemptsLeft) {
        var key = new AetherKey.CapacityReservationKey(node);
        var guard = new KVCommand.ReadWitness<AetherKey>(key, Option.some(reservation));

        return transact(key, List.of(guard), List.of(bumpMarker(node, marker(node)))).flatMap(done -> done
                                                                                                      ? Promise.unitPromise()
                                                                                                      : afterRestampConflict(node,
                                                                                                                             key,
                                                                                                                             reservation,
                                                                                                                             attemptsLeft));
    }

    private Promise<Unit> afterRestampConflict(NodeId node,
                                               AetherKey.CapacityReservationKey key,
                                               CapacityReservationValue read,
                                               int attemptsLeft) {
        var current = store.getTyped(key, CapacityReservationValue.class);

        if (current.isEmpty()) {
            return admission(node).flatMap(AetherValue.CapacityAdmissionValue::released)
                            .fold(() -> admitAgain(node, key, read),
                                  released -> admitAgain(node, key, released));
        }

        return attemptsLeft > 1
               ? restamp(node, current.unwrap(), attemptsLeft - 1)
               : Causes.cause("The re-stamp of the reservation of " + node.id() + " kept conflicting").promise();
    }

    /// The release of the previous incarnation committed first: the node is a member with no reservation and no slot. It is admitted again, as a new
    /// admission (an expected-absent reservation and, when counted, its slot in the ledger), keeping the invariant that the ledger counts the live
    /// reservations. A counted reservation the ledger cannot count again is a REFUSAL.
    private Promise<Unit> admitAgain(NodeId node,
                                     AetherKey.CapacityReservationKey key,
                                     CapacityReservationValue released) {
        var reservation = new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.none(), Option.some(released));
        var bump = bumpMarker(node, marker(node));
        var counted = !UNCOUNTED_BINDING.equals(released.sourceBinding());
        var accepted = counted
                       ? ledger().filter(CapacityLedgerValue::inventoryComplete)
                               .fold(() -> new ReadmissionRefused("The capacity ledger cannot count the slot of " + node.id()).<Boolean> promise(),
                                     current -> mutate(Option.some(current),
                                                       new CapacityLedgerValue(current.allocated() + 1,
                                                                               current.version() + 1,
                                                                               current.inventoryComplete()),
                                                       List.of(reservation, bump)))
                       : transact(key, List.of(), List.of(reservation, bump));

        return accepted.flatMap(done -> done
                                        ? Promise.unitPromise()
                                        : Causes.cause("The re-admission of " + node.id() + " conflicted").promise());
    }

    @Override
    public Promise<Unit> releaseExternal(NodeId node) {
        var key = new AetherKey.CapacityReservationKey(node);

        return store.getTyped(key, CapacityReservationValue.class)
                    .filter(value -> isExternalBinding(value.sourceBinding()))
                    .fold(Promise::unitPromise,
                          reservation -> releaseExternal(key, reservation));
    }

    /// A counted reservation returns its slot with the delete; an uncounted one (no ledger could count it) is dropped without touching the
    /// counter, ledger or not.
    private Promise<Unit> releaseExternal(AetherKey.CapacityReservationKey key, CapacityReservationValue reservation) {
        var drop = new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.some(reservation), Option.none());
        // The release commits only if the admission marker is still what it read - the marker mutation is a compare-and-set - and it leaves the marker
        // remembering the reservation it deleted. A join since the read bumped the marker, so a release that began before a rejoin cannot delete what the
        // new incarnation holds; a rejoin that finds the reservation already gone admits the node again from the remembered one.
        var remember = rememberReleased(key.nodeId(), marker(key.nodeId()), reservation);
        var counted = !UNCOUNTED_BINDING.equals(reservation.sourceBinding());
        var accepted = counted
                       ? ledger().fold(() -> transact(key, List.of(drop, remember)),
                                       current -> current.allocated() <= 0
                                                  ? Causes.cause("Capacity ledger is inconsistent with its reservation").<Boolean> promise()
                                                  : mutate(Option.some(current),
                                                           new CapacityLedgerValue(current.allocated() - 1,
                                                                                   current.version() + 1,
                                                                                   current.inventoryComplete()),
                                                           List.of(drop, remember)))
                       : transact(key, List.of(drop, remember));

        return accepted.flatMap(done -> done
                                        ? Promise.unitPromise()
                                        : Causes.cause("The release of the reservation of " + key.nodeId()
                                                                                                 .id()
                                                      + " conflicted; retry").promise());
    }

    private Promise<Boolean> transact(AetherKey key, List<KVCommand.Mutation<AetherKey, AetherValue>> changes) {
        return transact(key, List.of(), changes);
    }

    private Promise<Boolean> transact(AetherKey key,
                                      List<KVCommand.ReadWitness<AetherKey>> guards,
                                      List<KVCommand.Mutation<AetherKey, AetherValue>> changes) {
        return leader().fold(() -> Promise.success(false),
                             currentLeader -> {
                                 var id = UUID.randomUUID().toString();
                                 var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                       id,
                                                                                                       currentLeader,
                                                                                                       guards,
                                                                                                       changes);

                                 return apply.apply(List.of(command))
                                             .map(results -> results.stream()
                                                                    .filter(KVCommand.TransactionResult.class::isInstance)
                                                                    .map(KVCommand.TransactionResult.class::cast)
                                                                    .anyMatch(result -> result.transactionId()
                                                                                              .equals(id) && result.accepted()));
                             });
    }

    @Override
    public Promise<Unit> terminateNode(NodeId node) {
        if (externalNode(node)) {
            return Promise.unitPromise();
        }

        return source(node).fold(() -> Causes.cause("Cannot terminate without a committed capacity source binding").promise(),
                                 value -> terminateNode(node, value));
    }

    @Override
    public Promise<Unit> terminateNode(NodeId node, SourceName source) {
        if (externalNode(node)) {
            return Promise.unitPromise();
        }

        return ensureTerminationInventory().flatMap(_ -> store.getTyped(new AetherKey.CapacityReservationKey(node),
                                                                        CapacityReservationValue.class)
                                                              .toResult(Causes.cause("Cannot terminate without a committed capacity source binding"))
                                                              .async())
                                         .flatMap(reservation -> terminateBound(node,
                                                                                source,
                                                                                reservation.sourceBinding()));
    }

    private Promise<Unit> terminateBound(NodeId node, SourceName source, String binding) {
        if (leader().isEmpty()) {
            return Causes.cause("Core leadership required before provider termination").promise();
        }

        return store.getTyped(new AetherKey.CapacityReservationKey(node),
                              CapacityReservationValue.class)
                    .filter(value -> value.sourceName()
                                          .equals(source.value()) && value.sourceBinding()
                                                                          .equals(binding))
                    .toResult(Causes.cause("Termination does not match the committed capacity source binding"))
                    .async()
                    .flatMap(_ -> delegate.terminateNode(node, source, binding))
                    .flatMap(_ -> instancesForNode(node, source, binding).mapToUnit());
    }

    @Override
    public Promise<InstanceInfo> provisionNode(ProvisionSpec spec, String expectedBinding) {
        return provisionBound(spec, expectedBinding);
    }

    @Override
    public Promise<Unit> terminateNode(NodeId node, SourceName source, String expectedBinding) {
        return ensureTerminationInventory().flatMap(_ -> terminateBound(node, source, expectedBinding));
    }

    @Override
    public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source, String expectedBinding) {
        return delegate.instancesForNode(node, source, expectedBinding)
                       .flatMap(instances -> {
                                    if (leader().isEmpty()) {
                                        return Promise.success(instances);
                                    }

                                    return instances.isEmpty()
                                           ? releaseConfirmed(node, source, expectedBinding).map(_ -> instances)
                                           : observeBound(instances, source, expectedBinding).map(_ -> instances);
                                });
    }

    @Override
    public org.pragmatica.lang.Result<String> sourceBinding(SourceName source) {
        return delegate.sourceBinding(source);
    }

    @Override
    public boolean isCloudManaged() {
        return delegate.isCloudManaged();
    }

    @org.pragmatica.lang.Contract
    @Override
    public void resetProvisionerState(Option<ClusterName> name) {
        delegate.resetProvisionerState(name);
    }

    @Override
    public Promise<ActionResult> executeAction(NodeAction action) {
        return switch (action) {
            case NodeAction.StartNode start -> provisionNode(start.spec()).map(ActionResult.NodeStarted::new);
            case NodeAction.StopNode stop -> terminateNode(stop.nodeId()).map(_ -> new ActionResult.NodeStopped(stop.nodeId()));
            case NodeAction.MigrateSlices _ -> EnvironmentError.operationNotSupported("migrateSlices").promise();
        };
    }
}
