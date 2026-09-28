// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.*;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// #1561: capacity admission across the seed-to-operator-config transition, adopted from verifier v1561's probes.
/// The provider stub mirrors the real registry: under the seed every source binds `local`; under an operator
/// config each source binds its own account (`bound-<source>`).
class SeedToOperatorCapacityTest {
    private static final NodeId CORE = new NodeId("core");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final AtomicInteger creates = new AtomicInteger();
    private final List<String> terminatedBindings = new CopyOnWriteArrayList<>();
    private final Map<String, Promise<List<InstanceInfo>>> listedBySource = new ConcurrentHashMap<>();
    private final AtomicReference<Runnable> beforeReservationApply = new AtomicReference<>(() -> {});
    private final AtomicReference<Runnable> afterLedgerCreate = new AtomicReference<>(() -> {});

    private final NodeLifecycleManager delegate = new NodeLifecycleManager() {
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            creates.incrementAndGet();
            return Promise.success(instance(spec.context().nodeId().unwrap()));
        }
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec, String binding) { return provisionNode(spec); }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source, String binding) { return Promise.success(List.of()); }
        @Override public Promise<Unit> terminateNode(NodeId node, SourceName source, String binding) {
            terminatedBindings.add(binding);
            return Promise.unitPromise();
        }
        @Override public Promise<Unit> terminateNode(NodeId node) { return Promise.unitPromise(); }
        @Override public Promise<Unit> restartNode(NodeId node) { return Promise.unitPromise(); }
        @Override public Promise<ActionResult> executeAction(NodeAction action) { return Causes.cause("unused").promise(); }
        @Override public Result<String> sourceBinding(SourceName source) {
            return Result.success(SourceComputeRegistry.operatorConfig(config()).isEmpty()
                                  ? SourceComputeRegistry.LOCAL_SOURCE_BINDING
                                  : "bound-" + source.value());
        }
        @Override public boolean isCloudManaged() { return true; }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source) { return Promise.success(List.of()); }
        @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter, SourceName source, String binding) {
            return listedBySource.getOrDefault(source.value(), Promise.success(List.of()));
        }
    };

    /// `a` (seed-era reservation under source "default", bound `local`) is later listed by an operator
    /// `[source.default]`: it is adopted into the operator binding, and provisioning and termination continue.
    @Test
    void operatorDefaultSourceListsSeedEraNode_adoptsItAndKeepsProvisioningAndTerminating() {
        leader();
        commitSeed();
        var lc = lifecycle(10);
        assertThat(lc.provisionNode(spec("seed-node", "default")).await().isSuccess()).isTrue();
        listedBySource.put("default", Promise.success(List.of(instance("seed-node"))));
        commitOperator(2L, "default");

        var first = lc.provisionNode(spec("after-1", "default")).await();
        var second = lc.provisionNode(spec("after-2", "default")).await();
        var allocatedBeforeTerminate = ledger().allocated();
        var terminated = lc.terminateNode(new NodeId("seed-node"), SourceName.DEFAULT).await();

        assertThat(first.isSuccess()).as("(b-dup) provisioning after the operator config: %s", outcome(first)).isTrue();
        assertThat(second.isSuccess()).as("second provision: %s", outcome(second)).isTrue();
        assertThat(terminated.isSuccess()).as("terminate the adopted node: %s", outcome(terminated)).isTrue();
        assertThat(terminatedBindings).containsExactly("bound-default");
        assertThat(ledger().inventoryComplete()).isTrue();
        assertThat(allocatedBeforeTerminate).as("adoption does not count the seed-era node twice").isEqualTo(3);
        assertThat(ledger().allocated()).as("the confirmed termination released exactly one slot").isEqualTo(2);
    }

    /// (b-race) the operator config commits between reserveBound's inventory check and the reservation's apply:
    /// the reservation's config witness refuses it, so none lands before the operator sources are inventoried.
    @Test
    void operatorConfigCommitsBetweenCheckAndApply_reservationRefused() {
        leader();
        commitSeed();
        listedBySource.put("east", Promise.success(List.of(instance("existing-east"))));
        var lc = lifecycle(10);
        beforeReservationApply.set(() -> commitOperator(2L, "east"));

        var result = lc.provisionNode(spec("raced", "default")).await();

        assertThat(reservation("raced").isEmpty()).as("(b-race) a reservation committed AFTER the operator config replaced the seed, "
                                                     + "before its sources were inventoried").isTrue();
        assertThat(result.isFailure()).isTrue();
        assertThat(creates.get()).isZero();
    }

    /// The operator config commits after the inventory check passed vacuously under the seed but before
    /// reserveBound reads the ledger: reserveBound's own inventory check refuses (M5), and the next attempt
    /// inventories the new source and proceeds.
    @Test
    void operatorConfigCommitsAfterInventoryBeforeReservationCheck_refusesThenInventories() {
        leader();
        commitSeed();
        listedBySource.put("east", Promise.success(List.of(instance("existing-east"))));
        var lc = lifecycle(10);
        afterLedgerCreate.set(() -> commitOperator(2L, "east"));

        var refused = lc.provisionNode(spec("early", "east")).await();

        assertThat(refused.isFailure()).as("no reservation while the new operator source is uninventoried").isTrue();
        assertThat(reservation("early").isEmpty()).isTrue();
        assertThat(creates.get()).isZero();

        var retried = lc.provisionNode(spec("early", "east")).await();

        assertThat(retried.isSuccess()).as("retry: %s", outcome(retried)).isTrue();
        assertThat(reservation("existing-east").map(AetherValue.CapacityReservationValue::phase))
            .isEqualTo(Option.some(AetherValue.CapacityReservationPhase.OBSERVED));
    }

    /// (d) leader change mid-inventory: the old leader's inventory must not record completeness, and a retry after
    /// leadership returns re-runs it.
    @Test
    void leaderChangeMidInventory_doesNotRecordCompletenessAndRetryReinventories() {
        leader();
        commitSeed();
        var lc = lifecycle(10);
        lc.provisionNode(spec("seed-node", "other")).await();
        var pending = Promise.<List<InstanceInfo>>promise();
        listedBySource.put("east", pending);
        commitOperator(2L, "east");
        var first = lc.provisionNode(spec("n1", "east"));
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(new NodeId("other-core"), 2)));
        pending.succeed(List.of(instance("existing-east")));
        first.await();

        assertThat(ledger().inventoryComplete()).isFalse();
        assertThat(reservation("n1").isEmpty()).isTrue();

        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 3)));
        listedBySource.put("east", Promise.success(List.of(instance("existing-east"))));
        var retry = lc.provisionNode(spec("n1", "east")).await();

        assertThat(retry.isSuccess()).isTrue();
        assertThat(reservation("existing-east").map(AetherValue.CapacityReservationValue::phase))
            .isEqualTo(Option.some(AetherValue.CapacityReservationPhase.OBSERVED));
    }

    /// (a) The ledger counts ledger-made provisions only: a seed-only cluster whose cores formed outside the
    /// ledger admits a provision up to the limit regardless of them. Pins the changelog's scoped claim.
    @Test
    void seedClusterFormedOutsideLedger_ledgerCountsOnlyLedgerMadeProvisions() {
        leader();
        commitSeed();
        var lc = lifecycle(5);

        lc.provisionNode(spec("sixth", "default")).await();

        assertThat(creates.get()).as("(a) ledger admission with limit 5 on a 5-core seed cluster").isEqualTo(1);
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    /// (c) TRIPWIRE for the pre-existing gap the CTO is ticketing separately from #1561: a source ADDED by a later
    /// operator config is not inventoried, because completeness was already recorded for the earlier one. This
    /// asserts today's behaviour; when it reddens, the gap is fixed — delete it and assert the instance is counted.
    @Test
    void tripwire_sourceAddedByLaterOperatorConfig_isNotInventoried() {
        leader();
        commitOperator(2L, "east");
        var lc = lifecycle(10);
        lc.provisionNode(spec("n1", "east")).await();
        listedBySource.put("west", Promise.success(List.of(instance("existing-west"))));
        commitOperator(3L, "east", "west");

        lc.provisionNode(spec("n2", "east")).await();

        assertThat(reservation("existing-west").isPresent())
            .as("TRIPWIRE: an added source is now re-inventoried — the separately ticketed gap is fixed; delete this "
                + "tripwire and assert that existing-west is counted")
            .isFalse();
    }

    private static String toml(String... sources) {
        var sb = new StringBuilder("""
            config_version = "1.0.0"
            [cluster]
            name = "test"
            version = "1.0.0"
            """);
        for (var s : sources) {
            sb.append("[source.").append(s).append("]\n")
              .append("type = \"cloud\"\nprovider = \"hetzner\"\ncredentials = \"").append(s).append("-token\"\n")
              .append("region = \"").append(s).append("-region\"\n")
              .append("[source.").append(s).append(".core]\ncount = 5\ninstance_type = \"small\"\n");
        }
        return sb.toString();
    }

    private Option<AetherValue.ClusterConfigValue> config() {
        return store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
        var transactions = commands.stream()
                                   .filter(KVCommand.LeaderTransaction.class::isInstance)
                                   .map(KVCommand.LeaderTransaction.class::cast)
                                   .toList();
        var reserving = transactions.stream()
                                    .anyMatch(tx -> ((List<KVCommand.Mutation>) tx.mutations()).stream()
                                                                                                .anyMatch(m -> m.key() instanceof AetherKey.CapacityReservationKey
                                                                                                               && m.expected().isEmpty()));
        var creatingLedger = transactions.stream()
                                         .anyMatch(tx -> ((List<KVCommand.Mutation>) tx.mutations()).stream()
                                                                                                     .anyMatch(m -> m.key() instanceof AetherKey.CapacityLedgerKey
                                                                                                                    && m.expected().isEmpty()));
        if (reserving) {
            beforeReservationApply.getAndSet(() -> {}).run();
        }
        var results = store.process(store.createBatch(commands));
        if (creatingLedger) {
            afterLedgerCreate.getAndSet(() -> {}).run();
        }
        return Promise.success(results);
    }

    private NodeLifecycleManager lifecycle(int limit) {
        return CapacityControlledLifecycle.capacityControlledLifecycle(delegate, CORE, store, this::apply, () -> true, () -> limit);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private void leader() { seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER)); }

    private void commitSeed() {
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                 AetherValue.ClusterConfigValue.clusterConfigValue("", "test", "1.0.0",
                                                                                   List.of(new AetherValue.TopologyEntry("", "core", 5)),
                                                                                   3, 9, "bootstrap-seed", 1L)));
    }

    private void commitOperator(long version, String... sources) {
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                 AetherValue.ClusterConfigValue.clusterConfigValue(toml(sources), "test", "1.0.0",
                                                                                   List.of(new AetherValue.TopologyEntry(sources[0], "core", 5)),
                                                                                   3, 9, "cloud", version)));
    }

    private AetherValue.CapacityLedgerValue ledger() {
        return store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, AetherValue.CapacityLedgerValue.class).unwrap();
    }

    private Option<AetherValue.CapacityReservationValue> reservation(String node) {
        return store.getTyped(new AetherKey.CapacityReservationKey(new NodeId(node)), AetherValue.CapacityReservationValue.class);
    }

    private static ProvisionSpec spec(String node, String source) {
        return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "size", "core",
            ProvisionContext.forBootstrap(ClusterName.clusterName("test").unwrap(), "core", SourceName.sourceName(source).unwrap(), node)).unwrap();
    }

    private static InstanceInfo instance(String node) {
        return new InstanceInfo(new InstanceId("instance-" + node), InstanceStatus.RUNNING, List.of(), InstanceType.ON_DEMAND,
                                Map.of("aether-role", "core"), Option.some(node), Option.none());
    }

    private static String outcome(Result<?> result) {
        return result.fold(cause -> "FAIL: " + cause.message(), _ -> "OK");
    }
}
