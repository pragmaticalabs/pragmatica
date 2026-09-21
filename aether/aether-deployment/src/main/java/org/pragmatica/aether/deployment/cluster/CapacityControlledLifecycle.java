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
                                          AtomicBoolean initializing,
                                          AtomicBoolean inventoryReconciling,
                                          java.util.concurrent.atomic.AtomicReference<Option<AetherValue.ClusterConfigValue>> inventoriedConfiguration,
                                          java.util.Set<String> activeSources,
                                          java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> inventoryCursors) implements NodeLifecycleManager {
    public static CapacityControlledLifecycle capacityControlledLifecycle(NodeLifecycleManager delegate,
                                                                          NodeId self,
                                                                          KVStore<AetherKey, AetherValue> store,
                                                                          Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                                                                          BooleanSupplier activeLeader,
                                                                          IntSupplier limit) {
        return new CapacityControlledLifecycle(delegate,
                                               self,
                                               store,
                                               apply,
                                               activeLeader,
                                               limit,
                                               new AtomicBoolean(),
                                               new AtomicBoolean(),
                                               new java.util.concurrent.atomic.AtomicReference<>(Option.none()),
                                               new java.util.HashSet<>(),
                                               new java.util.concurrent.ConcurrentHashMap<>());
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

    @Override
    public Promise<Unit> reconcileInventory() {
        if (leader().isEmpty() || !inventoryReconciling.compareAndSet(false, true)) return Promise.unitPromise();

        return configuredInventory().fold(this::completeInventory);
    }

    private Promise<Unit> completeInventory(org.pragmatica.lang.Result<Unit> result) {
        inventoryReconciling.set(false);

        return result.async();
    }

    private Promise<Unit> configuredInventory() {
        return store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class)
                    .fold(Promise::unitPromise,
                          config -> ClusterBootstrapConfigParser.parse(config.tomlContent())
                                                                .async()
                                                                .flatMap(parsed -> ReconciliationBatch.reconcile(parsed.sources()
                                                                                                                       .values()
                                                                                                                       .stream()
                                                                                                                       .filter(source -> source.type() != SourceType.SSH)
                                                                                                                       .sorted(java.util.Comparator.comparing(source -> source.name()
                                                                                                                                                                              .value()))
                                                                                                                       .toList(),
                                                                                                                 4,
                                                                                                                 org.pragmatica.lang.io.TimeSpan.timeSpan(30)
                                                                                                                                                .seconds(),
                                                                                                                 source -> reconcileSource(parsed.cluster()
                                                                                                                                                 .name(),
                                                                                                                                           source.name()),
                                                                                                                 (source, cause) -> org.slf4j.LoggerFactory.getLogger(CapacityControlledLifecycle.class)
                                                                                                                                                           .warn("Capacity inventory for {} deferred: {}",
                                                                                                                                                                 source.name(),
                                                                                                                                                                 cause.message()))));
    }

    private Promise<Unit> reconcileSource(ClusterName cluster, SourceName source) {
        return delegate.sourceBinding(source)
                       .async()
                       .flatMap(binding -> listInstances(Map.of("aether-cluster",
                                                                cluster.value(),
                                                                "aether-source",
                                                                source.value()),
                                                         source,
                                                         binding).flatMap(instances -> confirmMissing(source,
                                                                                                      binding,
                                                                                                      instances)));
    }

    private Promise<Unit> confirmMissing(SourceName source, String binding, List<InstanceInfo> instances) {
        var observed = instances.stream()
                                .map(instance -> observedNode(instance, source))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var missing = reservations().entrySet()
                                  .stream()
                                  .filter(entry -> entry.getValue()
                                                        .sourceName()
                                                        .equals(source.value()))
                                  .filter(entry -> entry.getValue()
                                                        .sourceBinding()
                                                        .equals(binding))
                                  .filter(entry -> entry.getValue()
                                                        .phase() == CapacityReservationPhase.OBSERVED || entry.getValue()
                                                                                                              .phase() == CapacityReservationPhase.RETIRING)
                                  .filter(entry -> !observed.contains(entry.getKey()))
                                  .map(Map.Entry::getKey)
                                  .sorted(java.util.Comparator.comparing(NodeId::id))
                                  .toList();

        return ReconciliationBatch.reconcile(nextMissing(binding, missing),
                                             4,
                                             org.pragmatica.lang.io.TimeSpan.timeSpan(10).seconds(),
                                             node -> instancesForNode(node, source, binding).mapToUnit(),
                                             (node, cause) -> org.slf4j.LoggerFactory.getLogger(CapacityControlledLifecycle.class)
                                                                                     .warn("Capacity absence confirmation for {} deferred: {}",
                                                                                           node,
                                                                                           cause.message()));
    }

    private List<NodeId> nextMissing(String binding, List<NodeId> missing) {
        if (missing.size() <= 32) return missing;

        var cursor = inventoryCursors.computeIfAbsent(binding, _ -> new java.util.concurrent.atomic.AtomicInteger());
        var start = Math.floorMod(cursor.getAndAdd(32), missing.size());

        return java.util.stream.IntStream.range(0, 32)
                                         .mapToObj(offset -> missing.get((start + offset) % missing.size()))
                                         .toList();
    }

    @Override
    public java.util.Set<NodeId> allocatedNodes(String role) {
        return reservations().entrySet()
                           .stream()
                           .filter(entry -> entry.getValue()
                                                 .intendedRole()
                                                 .equalsIgnoreCase(role))
                           .filter(entry -> entry.getValue()
                                                 .phase() != CapacityReservationPhase.RETIRING)
                           .map(Map.Entry::getKey)
                           .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public java.util.Set<NodeId> retiringNodes(String role) {
        return reservations().entrySet()
                           .stream()
                           .filter(entry -> entry.getValue()
                                                 .intendedRole()
                                                 .equalsIgnoreCase(role))
                           .filter(entry -> entry.getValue()
                                                 .phase() == CapacityReservationPhase.RETIRING)
                           .map(Map.Entry::getKey)
                           .collect(java.util.stream.Collectors.toUnmodifiableSet());
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

    @Override
    public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
        return delegate.sourceBinding(spec.context().sourceName())
                       .async()
                       .flatMap(binding -> provisionBound(spec, binding));
    }

    private Promise<InstanceInfo> provisionBound(ProvisionSpec spec, String binding) {
        if (!acquireSource(binding)) return AdmissionFailure.CAPACITY_UNAVAILABLE.promise();

        return provisionAdmitted(spec, binding).timeout(org.pragmatica.aether.config.cluster.SourceProfile.DEFAULT_REPLACEMENT_CEILING)
                                .fold(result -> completeSource(binding, result));
    }

    private synchronized boolean acquireSource(String binding) {
        return activeSources.size() < 4 && activeSources.add(binding);
    }

    private synchronized Promise<InstanceInfo> completeSource(String binding,
                                                              org.pragmatica.lang.Result<InstanceInfo> result) {
        activeSources.remove(binding);

        return result.async();
    }

    private Promise<InstanceInfo> provisionAdmitted(ProvisionSpec spec, String binding) {
        return spec.context()
                   .nodeId()
                   .fold(() -> Causes.cause("Capacity reservation requires a stable node identity").promise(),
                         raw -> NodeId.nodeId(raw)
                                      .async()
                                      .flatMap(node -> existingAttempt(node).flatMap(_ -> admissionInventory())
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
                                                       return release(node,
                                                                      spec.context().sourceName(),
                                                                      binding,
                                                                      CapacityReservationPhase.DISPATCHED).flatMap(_ -> cause.promise());
                                                   }

                                                       return cause.promise();
                                                   },
                                                   instance -> observeBound(List.of(instance),
                                                                            spec.context().sourceName(),
                                                                            binding).map(_ -> instance)));
    }

    private Option<LeaderValue> leader() {
        return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                    .filter(value -> activeLeader.getAsBoolean() && value.leader()
                                                                         .equals(self));
    }

    private Option<CapacityLedgerValue> ledger() {
        return store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class);
    }

    private Promise<Unit> admissionInventory() {
        return ensureInventory().onFailure(cause -> org.slf4j.LoggerFactory.getLogger(CapacityControlledLifecycle.class)
                                                                           .warn("Capacity admission awaits complete inventory: {}",
                                                                                 cause.message()))
                              .fold(result -> result.fold(_ -> AdmissionFailure.INVENTORY_UNAVAILABLE.promise(),
                                                          Promise::success));
    }

    private Promise<Unit> ensureInventory() {
        var configuration = store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class);

        if (configuration.isEmpty()) return AdmissionFailure.INVENTORY_UNAVAILABLE.promise();

        if (ledger().filter(CapacityLedgerValue::inventoryComplete).isPresent() && inventoriedConfiguration.get()
                                                                                                           .equals(configuration)) {
            return Promise.unitPromise();
        }

        if (!initializing.compareAndSet(false, true)) {
            return Causes.cause("Fleet inventory initialization in progress").promise();
        }

        return initializeInventory().timeout(org.pragmatica.aether.config.cluster.SourceProfile.DEFAULT_REPLACEMENT_CEILING)
                                  .map(_ -> rememberInventory(configuration))
                                  .fold(this::completeInitialization);
    }

    private Unit rememberInventory(Option<AetherValue.ClusterConfigValue> configuration) {
        inventoriedConfiguration.set(configuration);

        return Unit.unit();
    }

    private Promise<Unit> completeInitialization(org.pragmatica.lang.Result<Unit> result) {
        initializing.set(false);

        return result.async();
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
        var configuration = store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class);

        if (!inventoriedConfiguration.get().equals(configuration) || store.get(key).isPresent() || current.filter(value -> value.inventoryComplete() && value.allocated() < limit.getAsInt())
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
                                                                                                                      CapacityReservationPhase.DISPATCHED)))),
                                            List.of(new KVCommand.ReadWitness<AetherKey>(AetherKey.ClusterConfigKey.CURRENT,
                                                                                         configuration.map(config -> (Object) config)))));
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

            if (existing.filter(value -> value.phase() == CapacityReservationPhase.OBSERVED || value.phase() == CapacityReservationPhase.RETIRING)
                        .isPresent()) {
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
        return mutate(before, after, changes, List.of());
    }

    private Promise<Boolean> mutate(Option<CapacityLedgerValue> before,
                                    CapacityLedgerValue after,
                                    List<KVCommand.Mutation<AetherKey, AetherValue>> changes,
                                    List<KVCommand.ReadWitness<AetherKey>> guards) {
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
        return store.getTyped(new AetherKey.CapacityReservationKey(node),
                              CapacityReservationValue.class)
                    .filter(value -> value.phase() == CapacityReservationPhase.OBSERVED || value.phase() == CapacityReservationPhase.RETIRING)
                    .fold(Promise::unitPromise,
                          value -> release(node,
                                           source,
                                           binding,
                                           value.phase()));
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
                    .flatMap(reservation -> recordRetirement(node, reservation))
                    .flatMap(_ -> leader().isPresent()
                                  ? delegate.terminateNode(node, source, binding)
                                  : HierarchyStateWriter.Refusal.INACTIVE.promise())
                    .flatMap(_ -> instancesForNode(node, source, binding).mapToUnit());
    }

    private Promise<Unit> recordRetirement(NodeId node, CapacityReservationValue before) {
        if (before.phase() == CapacityReservationPhase.RETIRING) return Promise.unitPromise();

        if (before.phase() != CapacityReservationPhase.OBSERVED) return HierarchyStateWriter.Refusal.CONFLICT.promise();

        var current = ledger();
        var next = current.map(value -> new CapacityLedgerValue(value.allocated(),
                                                                value.version() + 1,
                                                                value.inventoryComplete()));
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(new AetherKey.CapacityReservationKey(node),
                                                                      Option.some(before),
                                                                      Option.some(new CapacityReservationValue(before.sourceName(),
                                                                                                               before.sourceBinding(),
                                                                                                               before.intendedRole(),
                                                                                                               CapacityReservationPhase.RETIRING)));

        return next.fold(() -> HierarchyStateWriter.Refusal.CONFLICT.promise(),
                         value -> mutate(current, value, List.of(mutation)).flatMap(accepted -> accepted
                                                                                                ? Promise.unitPromise()
                                                                                                : HierarchyStateWriter.Refusal.CONFLICT.promise()));
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
