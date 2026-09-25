// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// One capacity admission boundary for core replacement, legacy scaling and community movement.
/// Provider failures retain their reservations: absence of an acknowledgement is not absence of a VM.
public record CapacityControlledLifecycle(NodeLifecycleManager delegate,
                                          NodeId self,
                                          KVStore<AetherKey, AetherValue> store,
                                          Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                                          BooleanSupplier activeLeader,
                                          IntSupplier limit,
                                          AtomicBoolean initializing) implements NodeLifecycleManager {
    public static NodeLifecycleManager capacityControlledLifecycle(NodeLifecycleManager delegate,
                                                                   NodeId self,
                                                                   KVStore<AetherKey, AetherValue> store,
                                                                   Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                                                                   BooleanSupplier activeLeader,
                                                                   IntSupplier limit) {
        return new CapacityControlledLifecycle(delegate, self, store, apply, activeLeader, limit, new AtomicBoolean());
    }

    public enum AdmissionFailure implements org.pragmatica.lang.Cause {
        CAPACITY_UNAVAILABLE;
        @Override
        public String message() {
            return "Fleet capacity reservation unavailable; no provider create was dispatched";
        }
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
                                      .flatMap(node -> existingAttempt(node).flatMap(_ -> ensureInventory())
                                                                      .flatMap(_ -> reserveBound(node,
                                                                                                 spec.context()
                                                                                                     .sourceName(),
                                                                                                 binding,
                                                                                                 spec.context().role()))
                                                                      .flatMap(accepted -> accepted
                                                                                           ? dispatch(node, spec)
                                                                                           : AdmissionFailure.CAPACITY_UNAVAILABLE.promise())));
    }

    private Promise<Unit> existingAttempt(NodeId node) {
        return store.get(new AetherKey.CapacityReservationKey(node))
                    .isPresent()
               ? Causes.cause("Existing capacity reservation has an unresolved or already dispatched provider operation").promise()
               : Promise.unitPromise();
    }

    private Promise<InstanceInfo> dispatch(NodeId node, ProvisionSpec spec) {
        if (leader().isEmpty()) {
            return Causes.cause("Core leadership changed before provider dispatch").promise();
        }

        return store.getTyped(new AetherKey.CapacityReservationKey(node),
                              CapacityReservationValue.class)
                    .toResult(Causes.cause("Missing committed capacity binding before create"))
                    .async()
                    .flatMap(reservation -> dispatchBound(node,
                                                          spec,
                                                          reservation.sourceBinding()));
    }

    private Promise<InstanceInfo> dispatchBound(NodeId node, ProvisionSpec spec, String binding) {
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
        return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                    .filter(value -> activeLeader.getAsBoolean() && value.leader()
                                                                         .equals(self));
    }

    private Option<CapacityLedgerValue> ledger() {
        return store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class);
    }

    private Promise<Unit> ensureInventory() {
        if (ledger().filter(CapacityLedgerValue::inventoryComplete).isPresent()) {
            return Promise.unitPromise();
        }

        if (!initializing.compareAndSet(false, true)) {
            return Causes.cause("Fleet inventory initialization in progress").promise();
        }

        return initializeInventory().onResultRun(() -> initializing.set(false));
    }

    private Promise<Unit> initializeInventory() {
        return store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class)
                    .fold(() -> Causes.cause("Committed source configuration required for fleet inventory").promise(),
                          config -> ClusterBootstrapConfigParser.parse(config.tomlContent())
                                                                .async()
                                                                .flatMap(parsed -> {
                                                                             var pass = Promise.unitPromise();

                                                                             for (var source : parsed.sources()
                                                                                                     .values()
                                                                                                     .stream()
                                                                                                     .filter(value -> value.type() != SourceType.SSH)
                                                                                                     .sorted(java.util.Comparator.comparing(value -> value.name()
                                                                                                                                                          .value()))
                                                                                                     .toList()) {
                                                                             pass = pass.flatMap(_ -> listInstances(Map.of("aether-source",
                                                                                                                           source.name()
                                                                                                                                 .value(),
                                                                                                                           "aether-cluster",
                                                                                                                           parsed.cluster()
                                                                                                                                 .name()
                                                                                                                                 .value())).mapToUnit());
                                                                         }

                                                                             return pass.flatMap(_ -> markInventoryComplete());
                                                                         }));
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

    private Promise<Boolean> reserveBound(NodeId node, SourceName source, String binding, String intendedRole) {
        var key = new AetherKey.CapacityReservationKey(node);
        var current = ledger();

        if (store.get(key).isPresent() || current.filter(value -> value.inventoryComplete() && value.allocated() < limit.getAsInt())
                                                 .isEmpty()) {
            return Promise.success(false);
        }

        return current.fold(() -> Promise.success(false),
                            value -> mutate(current,
                                            new CapacityLedgerValue(value.allocated() + 1, value.version() + 1, true),
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
                                                                                                       List.of(),
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
                                                                              List.of(new KVCommand.Mutation<>(key,
                                                                                                               Option.some(existing),
                                                                                                               Option.none()))).flatMap(accepted -> accepted
                                                                                                                                                    ? Promise.unitPromise()
                                                                                                                                                    : Causes.cause("Capacity release conflicted; retry absence confirmation").promise())));
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
                       .flatMap(instances -> {
                                    if (leader().isEmpty()) {
                                    return Promise.success(instances);
                                }

                                    var pass = Promise.unitPromise();

                                    for (int offset = 0; offset < instances.size(); offset += 128) {
                                    var batch = List.copyOf(instances.subList(offset,
                                                                              Math.min(offset + 128,
                                                                                       instances.size())));

                                    pass = pass.flatMap(_ -> observeBound(batch, source, binding));
                                }

                                    return pass.map(_ -> instances);
                                });
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
    public Promise<List<InstanceInfo>> instancesForNode(NodeId node) {
        return source(node).fold(() -> delegate.instancesForNode(node), value -> instancesForNode(node, value));
    }

    private Option<SourceName> source(NodeId node) {
        return store.getTyped(new AetherKey.CapacityReservationKey(node),
                              CapacityReservationValue.class)
                    .map(value -> SourceName.sourceNameOrDefault(value.sourceName()));
    }

    @Override
    public Promise<Unit> terminateNode(NodeId node) {
        return source(node).fold(() -> Causes.cause("Cannot terminate without a committed capacity source binding").promise(),
                                 value -> terminateNode(node, value));
    }

    @Override
    public Promise<Unit> terminateNode(NodeId node, SourceName source) {
        return ensureInventory().flatMap(_ -> store.getTyped(new AetherKey.CapacityReservationKey(node),
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
        return ensureInventory().flatMap(_ -> terminateBound(node, source, expectedBinding));
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
    public Promise<Unit> restartNode(NodeId node) {
        return delegate.restartNode(node);
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
            case NodeAction.RestartNode restart -> restartNode(restart.nodeId()).map(_ -> new ActionResult.NodeRestarted(restart.nodeId()));
            case NodeAction.MigrateSlices _ -> EnvironmentError.operationNotSupported("migrateSlices").promise();
        };
    }
}
