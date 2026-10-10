// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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


/// #1424: `POST /api/v1/cluster/upgrade` carried no client-side version fence, the only mutating cluster-config
/// route without one. Two operators issuing different `targetVersion`s, or an upgrade landing beside a scale or
/// apply, resolved as last intent wins: the store-level successor CAS closes the lost update but nothing let an
/// operator say "upgrade only if the config is still at version N". The fence now matches apply-config (#289) and
/// scale (#1086): a stale non-zero `expectedVersion` is a 409 `VersionConflict`, and `expectedVersion=0` against
/// a populated config is a 409 `UnfencedOverwrite`, never a wildcard. Every pin goes through the real route handler.
class ClusterConfigRoutesUpgradeFenceTest {
    private static final TopologyEntry CORE_3 = new TopologyEntry("eu", "core", 3);

    @Test
    void handleUpgrade_matchingExpectedVersion_isAllowed_andBumpsTheConfigVersion() {
        var store = storeWith(committedConfig(1));
        var response = upgrade(store, new UpgradeRequest("1.1.0", 1));

        assertThat(response.isSuccess()).as("a fenced upgrade at the read version must land: " + response).isTrue();
        assertThat(store.get(ClusterConfigKey.CURRENT).map(ClusterConfigValue.class::cast).unwrap().configVersion()).isEqualTo(2);
    }

    /// The #1424 defect: with no client fence, an upgrade issued against a config another operator has
    /// already moved on (stored 2, caller read 1) was re-read and committed on top.
    @Test
    void handleUpgrade_staleExpectedVersion_isRefusedAsVersionConflict_beforeAnyWrite() {
        var store = storeWith(committedConfig(2));
        var result = upgrade(store, new UpgradeRequest("1.1.0", 1));

        assertThat(result.isFailure()).as("a stale expectedVersion must be refused, got: " + result).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ClusterConfigError.VersionConflict.class);
            assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        assertUnchanged(store, 2, "1.0.0");
    }

    /// `expectedVersion=0` is the fresh-cluster bypass of `checkVersionAsync`; every config an upgrade can reach
    /// is populated, so here it is an unfenced overwrite, refused like on apply-config and scale.
    @Test
    void handleUpgrade_populatedConfigWithZeroExpectedVersion_isRefusedAsUnfencedOverwrite_beforeAnyWrite() {
        var store = storeWith(committedConfig(1));
        var result = upgrade(store, new UpgradeRequest("1.1.0", 0));

        assertThat(result.isFailure()).as("expectedVersion=0 against a populated config must be refused, got: " + result).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ClusterConfigError.UnfencedOverwrite.class);
            assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        assertUnchanged(store, 1, "1.0.0");
    }

    /// Ordering pin: a no-op upgrade keeps its own answer whatever version the caller holds (the CLI treats it
    /// as success), so the fence sits after the already-at-version check.
    @Test
    void handleUpgrade_alreadyAtTargetVersion_answersAlreadyAtVersion_notTheFence() {
        var store = storeWith(committedConfig(1));
        var result = upgrade(store, new UpgradeRequest("1.0.0", 0));

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigRoutes.UpgradeError.AlreadyAtVersion.class));
        assertUnchanged(store, 1, "1.0.0");
    }

    /// An OMITTED or null `expectedVersion` is not 0: the field is a primitive `long`, the wired codec refuses
    /// the body at decode time and `RequestContext.jsonBody` answers 400 before the route runs. This is the
    /// breaking change for a client that omitted it.
    @Test
    void upgradeRequest_omittedExpectedVersion_isRefusedAtDecode_neverReadAsZero() {
        assertThat(decodeThroughWiredCodec("{\"targetVersion\":\"1.1.0\"}").isFailure()).isTrue();
        assertThat(decodeThroughWiredCodec("{\"targetVersion\":\"1.1.0\",\"expectedVersion\":null}").isFailure()).isTrue();
    }

    /// Positive control for the pin above: an explicit value DOES decode, so its failure is the missing field.
    @Test
    void upgradeRequest_explicitExpectedVersion_decodes() {
        var decoded = decodeThroughWiredCodec("{\"targetVersion\":\"1.1.0\",\"expectedVersion\":7}");

        assertThat(decoded.isSuccess()).as(decoded.toString()).isTrue();
        assertThat(decoded.unwrap().expectedVersion()).isEqualTo(7L);
    }

    private static Result<UpgradeRequest> decodeThroughWiredCodec(String json) {
        return JsonCodecAdapter.defaultCodec().deserialize(json.getBytes(StandardCharsets.UTF_8),
                                                           TypeToken.typeToken(UpgradeRequest.class));
    }

    private static void assertUnchanged(TestKVStore store, long configVersion, String version) {
        var committed = store.get(ClusterConfigKey.CURRENT)
                             .filter(ClusterConfigValue.class::isInstance)
                             .map(ClusterConfigValue.class::cast)
                             .unwrap();

        assertThat(committed.configVersion()).as("no write may land behind a refusal").isEqualTo(configVersion);
        assertThat(committed.version()).as("no version change may land behind a refusal").isEqualTo(version);
    }

    private static Result<UpgradeResponse> upgrade(TestKVStore store, UpgradeRequest request) {
        return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store))
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

    private static ManageableNode nodeWith(TestKVStore store) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> dispatch(store, method, args));
    }

    private static Object dispatch(TestKVStore store, Method method, Object[] args) {
        return switch (method.getName()) {
            case "kvStore" -> store;
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
