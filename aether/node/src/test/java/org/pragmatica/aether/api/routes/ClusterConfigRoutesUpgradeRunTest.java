// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import org.pragmatica.aether.deployment.cluster.UpgradeRunService;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeStop;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.pragmatica.aether.api.ManagementApiResponses.UpgradeRequest;
import org.pragmatica.aether.api.ManagementApiResponses.UpgradeResponse;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1543 F — `POST /api/v1/cluster/upgrade` stores the version AND starts the rolling run, through the real route handler. The Ember
/// tests call the run service directly, so without these the headline feature (the upgrade route rolls the cluster) was unpinned: a route
/// that stored the version and never started anything passed every other test.
class ClusterConfigRoutesUpgradeRunTest {
    private static final TopologyEntry CORE_3 = new TopologyEntry("eu", "core", 3);

    /// A run service that records what it was asked and answers as scripted.
    private static final class Runs implements UpgradeRunService {
        final List<String> started = new ArrayList<>();
        Result<UpgradeRunValue> startAnswer = Result.success(run(UpgradeRunState.RUNNING));
        Option<UpgradeRunValue> status = Option.none();

        @Override
        public Promise<UpgradeRunValue> start(String targetVersion) {
            started.add(targetVersion);

            return Promise.resolved(startAnswer);
        }

        @Override
        public Option<UpgradeRunValue> status() {
            return status;
        }

        @Override
        public Promise<UpgradeRunValue> pause() {
            return new Refusal.Unavailable().promise();
        }

        @Override
        public Promise<UpgradeRunValue> resume() {
            return new Refusal.Unavailable().promise();
        }

        @Override
        public Promise<UpgradeRunValue> abort() {
            return new Refusal.Unavailable().promise();
        }
    }

    private static UpgradeRunValue run(UpgradeRunState state) {
        return new UpgradeRunValue("1.1.0", List.of(new NodeId("a")), 0, "", state, UpgradeStop.NONE, "", 1L, 1L, 1L);
    }

    @Test
    void anUpgrade_storesTheVersion_thenStartsTheRunTowardsIt() {
        var store = storeWith(committedConfig(1));
        var runs = new Runs();
        var result = upgrade(store, runs, new UpgradeRequest("1.1.0", 1));

        assertThat(result.isSuccess()).as(result.toString()).isTrue();
        assertThat(runs.started).as("the run was started, once, towards the stored version").containsExactly("1.1.0");
        assertThat(store.get(ClusterConfigKey.CURRENT).map(ClusterConfigValue.class::cast).unwrap().version()).isEqualTo("1.1.0");
    }

    @Test
    void aRefusedStart_isSurfaced_notSwallowed() {
        var store = storeWith(committedConfig(1));
        var runs = new Runs();

        runs.startAnswer = new UpgradeRunService.Refusal.NotLeader().result();

        var result = upgrade(store, runs, new UpgradeRequest("1.1.0", 1));

        assertThat(result.isFailure()).as("the version is stored but no run started: the caller must be told").isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(UpgradeRunService.Refusal.NotLeader.class));
    }

    @Test
    void nothingToReplace_leavesTheStoredVersionAsTheWholeEffect() {
        var store = storeWith(committedConfig(1));
        var runs = new Runs();

        runs.startAnswer = new UpgradeRunService.Refusal.NothingToReplace("1.1.0").result();

        assertThat(upgrade(store, runs, new UpgradeRequest("1.1.0", 1)).isSuccess()).isTrue();
    }

    @Test
    void aLiveRunTowardsAnotherVersion_refusesTheUpgrade_beforeAnythingIsStored() {
        var store = storeWith(committedConfig(1));
        var runs = new Runs();

        runs.status = Option.some(run(UpgradeRunState.RUNNING));

        var result = upgrade(store, runs, new UpgradeRequest("1.2.0", 1));

        assertThat(result.isFailure()).isTrue();
        assertThat(runs.started).isEmpty();
        assertThat(store.get(ClusterConfigKey.CURRENT).map(ClusterConfigValue.class::cast).unwrap().version()).as("nothing stored").isEqualTo("1.0.0");
    }

    @Test
    void theSameVersionReissued_startsTheRunThatIsStillOwed_andOtherwiseAnswersAlreadyAtVersion() {
        var store = storeWith(committedConfig(1));
        var runs = new Runs();

        assertThat(upgrade(store, runs, new UpgradeRequest("1.0.0", 1)).isSuccess()).as("a run is owed: it is started").isTrue();
        assertThat(runs.started).containsExactly("1.0.0");

        runs.startAnswer = new UpgradeRunService.Refusal.NothingToReplace("1.0.0").result();

        var settled = upgrade(store, runs, new UpgradeRequest("1.0.0", 1));

        assertThat(settled.isFailure()).isTrue();
        settled.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigRoutes.UpgradeError.AlreadyAtVersion.class));

        runs.startAnswer = new UpgradeRunService.Refusal.NotLeader().result();

        var refused = upgrade(store, runs, new UpgradeRequest("1.0.0", 1));

        refused.onFailure(cause -> assertThat(cause).as("a real refusal is not reported as 'already at version'").isInstanceOf(UpgradeRunService.Refusal.NotLeader.class));
    }

    private static Result<UpgradeResponse> upgrade(TestKVStore store, UpgradeRunService runs, UpgradeRequest request) {
        return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store, runs))
                                  .handleUpgrade(request)
                                  .await();
    }

    private static ClusterConfigValue committedConfig(long configVersion) {
        // Blank seed TOML: every committed config is re-read for [replication.cluster_events] (#1564 B1), and the
        // placeholder "toml" this used before is not a TOML document.
        return ClusterConfigValue.bootstrapSeed("prod",
                                                     "1.0.0",
                                                     List.of(CORE_3),
                                                     3,
                                                     9,
                                                     "hetzner",
                                                     configVersion);
    }

    private static TestKVStore storeWith(ClusterConfigValue committed) {
        var store = new TestKVStore();

        store.seed(ClusterConfigKey.CURRENT, committed);

        return store;
    }

    private static ManageableNode nodeWith(TestKVStore store, UpgradeRunService runs) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> dispatch(store, runs, method, args));
    }

    private static Object dispatch(TestKVStore store, UpgradeRunService runs, Method method, Object[] args) {
        return switch (method.getName()) {
            case "kvStore" -> store;
            case "upgradeRunService" -> runs;
            case "isLeader" -> true;
            case "apply" -> applyBatch(store, args);
            default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
        };
    }

    @SuppressWarnings("unchecked")
    private static Promise<List<Object>> applyBatch(TestKVStore store, Object[] args) {
        return Promise.success(((List<KVCommand<AetherKey>>) args[0]).stream()
                                                                      .map(command -> routeCommand(store, command))
                                                                      .toList());
    }

    /// #1390 commits a config update as ONE leader transaction carrying one compare-and-set mutation
    /// (`ClusterConfigRoutes.storeFencedConfig`), where rc4 issued a bare `Put`. Applying that mutation
    /// keeps an accepted write OBSERVABLE, which is what makes the RED case red — the same port #1390
    /// made to [ClusterConfigRoutesScaleNoConfigTest].
    private static Object routeCommand(TestKVStore store, KVCommand<AetherKey> command) {
        return command instanceof KVCommand.LeaderTransaction<AetherKey, ?> transaction
               ? applyTransaction(store, transaction)
               : new KVCommand.TransactionResult("unexpected", false);
    }

    private static KVCommand.TransactionResult applyTransaction(TestKVStore store,
                                                                KVCommand.LeaderTransaction<AetherKey, ?> transaction) {
        var mutation = transaction.mutations().getFirst();
        var accepted = store.get(mutation.key()).equals(mutation.expected());

        if (accepted) {
            mutation.replacement().onPresent(value -> store.applyPut(mutation.key(), (AetherValue) value));
        }

        return new KVCommand.TransactionResult(transaction.transactionId(), accepted);
    }

    /// A plain map store, as in [ClusterConfigRoutesScaleNoConfigTest]: the RFC-0018 successor fence
    /// is pinned in [ClusterConfigRoutesApplyTest]; this harness only needs a store that round-trips the
    /// transaction's write so an accepted write is OBSERVABLE — which is what makes the RED case red.
    private static final class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();

        private TestKVStore() {
            super(null, null, null);
        }

        void seed(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        void applyPut(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        /// The route reads the committed leader before it builds its transaction (#1390).
        @Override
        public <VV> Option<VV> getTyped(StructuredKey key, Class<VV> type) {
            return key == LeaderKey.INSTANCE
                   ? Option.some(type.cast(new LeaderValue(new NodeId("core"), 1)))
                   : Option.option(storage.get(key)).filter(type::isInstance).map(type::cast);
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            return new HashMap<>(storage);
        }

        @Override
        public Option<AetherValue> get(AetherKey key) {
            return Option.option(storage.get(key));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
            storage.forEach((key, value) -> {
                if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                    consumer.accept((KK) key, (VV) value);
                }
            });
        }
    }
}
