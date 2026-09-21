// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.pragmatica.aether.api.ManagementApiResponses.ScaleClusterResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ScaleRequest;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1086: `POST /api/v1/cluster/scale` honoured `expectedVersion=0` as a wildcard. `checkVersionAsync`
/// treats 0 as the "fresh cluster" bypass sentinel, and the #289 `isUnfencedOverwrite` refusal sat on
/// the apply-config path only — so a scale body carrying the zero default (or omitting the field, which
/// Jackson reads as 0) rewrote a populated config's desired count with no fence at all. Probed live on
/// PR #1070's review: 5→7 with `expectedVersion:0` against `storedVersion=1` answered
/// `HTTP 200 … configVersion:2`.
///
/// The guarantee pinned here, through the real route handler: a scale request whose `expectedVersion`
/// differs from the committed `configVersion` is refused with a 409 before any write, and
/// `expectedVersion=0` against a populated config is a mismatch (`UnfencedOverwrite`), not a wildcard.
/// The refusal sits where #289 put it on apply-config — at the write, after the scale validator — so a
/// request the validator would refuse anyway still carries the validator's own cause.
class ClusterConfigRoutesScaleFenceTest {
    private static final TopologyEntry CORE_3 = new TopologyEntry("eu", "core", 3);

    /// The #1086 defect: on the unmodified base this scale is ACCEPTED and the committed count becomes 5
    /// at configVersion 2.
    @Test
    void handleScale_populatedConfigWithZeroExpectedVersion_isRefusedAsUnfencedOverwrite_beforeAnyWrite() {
        var store = storeWith(committedConfig(1));
        var result = scale(store, new ScaleRequest("eu", "core", 5, 0));

        assertThat(result.isFailure()).as("expectedVersion=0 against a populated config must be refused, got: " + result)
                  .isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ClusterConfigError.UnfencedOverwrite.class);
            assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(cause.message()).contains("expectedVersion=0", "version 1");
        });
        assertUnchanged(store, 1, 3);
    }

    /// The fence is keyed on the STORED version, exactly as `isUnfencedOverwrite` states it: 0 against
    /// 0 is the fresh-cluster case and stays allowed.
    @Test
    void handleScale_unversionedConfigWithZeroExpectedVersion_isAllowed() {
        var store = storeWith(committedConfig(0));
        var response = scaleSucceeds(store, new ScaleRequest("eu", "core", 5, 0));

        assertThat(response.previousCount()).isEqualTo(3);
        assertThat(response.newCount()).isEqualTo(5);
        assertThat(response.configVersion()).isEqualTo(1);
    }

    @Test
    void handleScale_populatedConfigWithMatchingExpectedVersion_isAllowed() {
        var store = storeWith(committedConfig(1));
        var response = scaleSucceeds(store, new ScaleRequest("eu", "core", 5, 1));

        assertThat(response.newCount()).isEqualTo(5);
        assertThat(response.configVersion()).isEqualTo(2);
    }

    /// Control: a stale NON-ZERO version was already refused on the base through `checkVersionAsync`.
    @Test
    void handleScale_populatedConfigWithStaleExpectedVersion_isRefusedAsVersionConflict() {
        var store = storeWith(committedConfig(2));
        var result = scale(store, new ScaleRequest("eu", "core", 5, 1));

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ClusterConfigError.VersionConflict.class);
            assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        assertUnchanged(store, 2, 3);
    }

    /// Ordering pin — the fence guards the write, it does not pre-empt the validator. A scale to 1 core
    /// is a quorum violation whatever version the caller holds; `03-scaling/test-01-quorum-safety.sh`
    /// probes the validator with `expectedVersion:0` bodies and must keep seeing the validator's own
    /// refusal, not the fence's. Same placement as #289 on apply-config (after the immutable-field and
    /// no-op checks, immediately before the write).
    @Test
    void handleScale_populatedConfigWithZeroExpectedVersion_validatorRefusalStillWins() {
        var store = storeWith(committedConfig(1));
        var result = scale(store, new ScaleRequest("eu", "core", 1, 0));

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.QuorumSafetyViolation.class));
        assertUnchanged(store, 1, 3);
    }

    private static void assertUnchanged(TestKVStore store, long configVersion, int coreCount) {
        var committed = store.get(ClusterConfigKey.CURRENT)
                             .filter(ClusterConfigValue.class::isInstance)
                             .map(ClusterConfigValue.class::cast)
                             .unwrap();

        assertThat(committed.configVersion()).as("no write may land behind a refusal").isEqualTo(configVersion);
        assertThat(committed.desiredCountFor("eu", "core")).isEqualTo(coreCount);
    }

    private static Result<ScaleClusterResponse> scale(TestKVStore store, ScaleRequest request) {
        return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store))
                                  .handleScale(request)
                                  .await();
    }

    private static ScaleClusterResponse scaleSucceeds(TestKVStore store, ScaleRequest request) {
        var result = scale(store, request);

        assertThat(result.isSuccess()).as("scale must be accepted, got: " + result).isTrue();

        return result.unwrap();
    }

    private static ClusterConfigValue committedConfig(long configVersion) {
        return ClusterConfigValue.clusterConfigValue("toml",
                                                     "prod",
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
            case "apply" -> applyBatch(store, args);
            default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
        };
    }

    @SuppressWarnings("unchecked")
    private static Promise<List<Object>> applyBatch(TestKVStore store, Object[] args) {
        ((List<KVCommand<AetherKey>>) args[0]).forEach(command -> routeCommand(store, command));

        return Promise.success(List.of());
    }

    private static void routeCommand(TestKVStore store, KVCommand<AetherKey> command) {
        if (command instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof AetherValue value) {
            store.applyPut(put.key(), value);
        }
    }

    /// Unconditional write, as in [ClusterConfigRoutesScaleNoConfigTest]: the RFC-0018 successor fence
    /// is pinned in [ClusterConfigRoutesApplyTest]; this harness only needs a store that round-trips a
    /// `Put` so an accepted write is OBSERVABLE — which is what makes the RED case red.
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
