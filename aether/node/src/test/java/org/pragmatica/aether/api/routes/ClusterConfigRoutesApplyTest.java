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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.api.ManagementApiResponses.ApplyConfigRequest;
import org.pragmatica.aether.api.ManagementApiResponses.ApplyConfigResponse;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// `POST /api/cluster/config` — the three defects that made RFC-0017 worker provisioning a no-op,
/// each proven on a live Hetzner cluster before being pinned here.
///
/// Observed on the wire at formation: every node self-seeds a `ClusterConfigValue` with
/// `tomlContent=""`, the bootstrap CLI POSTs the operator's real TOML (carrying
/// `[source.hetzner.worker] count = 2`) with `expectedVersion=0`, the route answers with a success
/// `ApplyConfigResponse` — and the committed config remains the core-only seed. The worker topology
/// entry never reaches cluster state, so `reconcileWorkerTopology` has nothing to reconcile and no
/// cloud worker is ever created. Re-POSTing reproduced it exactly: success, `updatedAt` unmoved.
///
/// The three faults compose, which is why the failure was silent rather than loud:
///   1. routing recovered from ANY apply failure into the first-time store, so a refusal became a
///      fresh write attempt;
///   2. the apply against a blank-TOML seed always failed (the parser cannot clear its
///      `config_version` gate on `""`), which is the failure that was then recovered from;
///   3. that first-time store issued a bare `Put` and reported success unconditionally, while the
///      RFC-0018 successor fence rejected it SILENTLY — a version-1 write against a committed
///      version-1 seed is not a successor.
///
/// [TestKVStore] therefore models the successor fence rather than accepting every write: a fake that
/// stores whatever it is handed cannot observe defect 3 at all, since the whole defect is a
/// rejection that leaves no trace in the apply result.
class ClusterConfigRoutesApplyTest {
    /// The live RFC-0017 shape: one cloud source declaring BOTH core and worker counts.
    private static final String OPERATOR_TOML = """
        config_version = "1.0.0"

        [cluster]
        name = "prod"
        version = "1.0.0"

        [runtime.node]
        type = "container"
        image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"

        [source.hetzner]
        type = "cloud"
        provider = "hetzner"
        region = "eu-central"

        [source.hetzner.core]
        count = 3
        runtime = "node"

        [source.hetzner.worker]
        count = 2
        runtime = "node"
        """;

    /// Same cluster, more workers — a genuine mutation, so the diff is non-empty.
    private static final String SCALED_TOML = OPERATOR_TOML.replace("count = 2", "count = 4");

    /// `cluster.name` is the one immutable field, so this is the cheapest way to make the diff refuse.
    private static final String RENAMED_TOML = OPERATOR_TOML.replace("name = \"prod\"", "name = \"staging\"");

    private static final TopologyEntry CORE_ENTRY = new TopologyEntry("hetzner", "core", 3);
    private static final TopologyEntry WORKER_ENTRY = new TopologyEntry("hetzner", "worker", 2);

    @Nested
    class SeedReplacement {

        /// The defect, end to end: a seed carries no diffable TOML, so it can only be REPLACED —
        /// which is what BootstrapModule's own comment ("Bootstrap replaces it with the real
        /// per-source spec at formation") always claimed happened.
        @Test
        void handleApplyConfig_storedBlankTomlSeed_replacesSeedWithFullOperatorTopology() {
            var store = storeWith(bootstrapSeed());

            var response = applySucceeds(store, new ApplyConfigRequest(OPERATOR_TOML, 0));
            var committed = committedConfig(store);

            assertThat(committed.tomlContent()).isEqualTo(OPERATOR_TOML);
            assertThat(committed.desiredTopology()).containsExactlyInAnyOrder(CORE_ENTRY, WORKER_ENTRY);
            assertThat(committed.configVersion()).as("seed version + 1, so the successor fence accepts the write")
                                                 .isEqualTo(2);
            assertThat(response.configVersion()).isEqualTo(2);
        }

        /// The operator-visible symptom: without the worker entry in committed state,
        /// `reconcileWorkerTopology` has nothing to act on and no cloud worker is ever created.
        @Test
        void handleApplyConfig_storedBlankTomlSeed_carriesWorkerCountIntoClusterState() {
            var store = storeWith(bootstrapSeed());

            applySucceeds(store, new ApplyConfigRequest(OPERATOR_TOML, 0));

            assertThat(committedConfig(store).desiredCountFor("hetzner", "worker")).isEqualTo(2);
        }

        /// The seed's placeholder entry (empty source name) must not survive alongside the real one,
        /// or the topology would declare a phantom source.
        @Test
        void handleApplyConfig_storedBlankTomlSeed_dropsThePlaceholderSourceEntry() {
            var store = storeWith(bootstrapSeed());

            applySucceeds(store, new ApplyConfigRequest(OPERATOR_TOML, 0));

            assertThat(committedConfig(store).desiredTopology()).noneMatch(entry -> entry.sourceName().isEmpty());
            assertThat(committedConfig(store).deploymentType()).isEqualTo("cloud");
        }

        @Test
        void isBootstrapSeed_blankTomlContent_isSeed() {
            assertThat(ClusterConfigRoutes.isBootstrapSeed(bootstrapSeed())).isTrue();
        }

        @Test
        void isBootstrapSeed_realTomlContent_isNotSeed() {
            assertThat(ClusterConfigRoutes.isBootstrapSeed(committedOperatorConfig(1))).isFalse();
        }
    }

    @Nested
    class FailurePropagation {

        /// Defect 1. The refusal must reach the operator. Previously `.orElse(storeInitialConfig)`
        /// could not tell this failure from "nothing stored yet", so a rejected rename was answered
        /// with a success response for a phantom first-time store.
        @Test
        void handleApplyConfig_immutableFieldChange_propagatesFailureWithoutPhantomInitialStore() {
            var store = storeWith(committedOperatorConfig(5));

            var result = apply(store, new ApplyConfigRequest(RENAMED_TOML, 5));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.ValidationFailed.class));
            assertUnchanged(store, 5);
        }

        @Test
        void handleApplyConfig_versionConflict_propagatesFailureWithoutPhantomInitialStore() {
            var store = storeWith(committedOperatorConfig(5));

            var result = apply(store, new ApplyConfigRequest(SCALED_TOML, 3));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.VersionConflict.class));
            assertUnchanged(store, 5);
        }

        /// The #289 fence, reached through the whole route rather than through its pure predicate:
        /// an unfenced re-push against populated config is refused, and refusal does not fall through
        /// to a first-time store either.
        @Test
        void handleApplyConfig_unfencedOverwriteOfPopulatedConfig_propagatesFailure() {
            var store = storeWith(committedOperatorConfig(5));

            var result = apply(store, new ApplyConfigRequest(SCALED_TOML, 0));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.UnfencedOverwrite.class));
            assertUnchanged(store, 5);
        }

        private static void assertUnchanged(TestKVStore store, long expectedVersion) {
            var committed = committedConfig(store);

            assertThat(committed.clusterName()).isEqualTo("prod");
            assertThat(committed.tomlContent()).isEqualTo(OPERATOR_TOML);
            assertThat(committed.configVersion()).isEqualTo(expectedVersion);
        }
    }

    @Nested
    class InitialStore {

        @Test
        void handleApplyConfig_emptyKvStore_storesEveryDeclaredSourceRolePairAtVersionOne() {
            var store = new TestKVStore();

            var response = applySucceeds(store, new ApplyConfigRequest(OPERATOR_TOML, 0));
            var committed = committedConfig(store);

            assertThat(committed.desiredTopology()).containsExactlyInAnyOrder(CORE_ENTRY, WORKER_ENTRY);
            assertThat(committed.configVersion()).isEqualTo(1);
            assertThat(committed.clusterName()).isEqualTo("prod");
            assertThat(response.configVersion()).isEqualTo(1);
            assertThat(response.coreCount()).isEqualTo(3);
        }

        /// Defect 3. The first-time store used a bare `Put` and reported success unconditionally,
        /// but the successor fence rejects silently — nothing in the apply result reveals the loss.
        /// A write that did not land must be a failure, never a fabricated version and timestamp.
        @Test
        void handleApplyConfig_initialStoreThatDoesNotLand_failsInsteadOfReportingSuccess() {
            var store = new TestKVStore();

            store.loseWritesTo(committedOperatorConfig(9));

            var result = apply(store, new ApplyConfigRequest(SCALED_TOML, 0));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.VersionConflict.class));
            assertThat(committedConfig(store).tomlContent()).as("the competing writer's config is what committed")
                                                            .isEqualTo(OPERATOR_TOML);
        }
    }

    @Nested
    class ScaleReachesApplier {

        /// #578 review Testing Gap T4: none of the other tests in this file exercise a diff that
        /// actually reaches `applier.apply(plan.allActions())` (`ClusterConfigRoutes.executeDiff`) —
        /// they either replace a bootstrap seed (bypasses the applier entirely) or fail before the
        /// diff is even computed. A genuine worker-count scale against a real, matching-version
        /// committed config is the smallest diff that reaches the applier. This harness wires
        /// `ClusterConfigApplier.NoTopologyManager` (the single-arg `clusterConfigRoutes` factory's
        /// applier), so the observable proof of reachability is the applier's own honest 503 — not a
        /// fabricated success — and a rejected apply must leave the previously committed config
        /// untouched, same guarantee the applier's own `apply()` gives for any rejected plan.
        @Test
        void handleApplyConfig_genuineScale_reachesApplier_andFailsHonestlyWithNoTopologyManagerWired() {
            var store = storeWith(committedOperatorConfig(5));

            var result = apply(store, new ApplyConfigRequest(SCALED_TOML, 5));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.ClusterTopologyManagerUnavailable.class));

            var committed = committedConfig(store);
            assertThat(committed.tomlContent()).as("a rejected apply must leave the previously committed config untouched")
                                               .isEqualTo(OPERATOR_TOML);
            assertThat(committed.configVersion()).isEqualTo(5);
        }
    }

    private static Result<Object> apply(TestKVStore store, ApplyConfigRequest request) {
        return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store))
                                  .handleApplyConfig(request)
                                  .await();
    }

    private static ApplyConfigResponse applySucceeds(TestKVStore store, ApplyConfigRequest request) {
        return apply(store, request).onFailure(cause -> fail(cause.message()))
                                    .map(ApplyConfigResponse.class::cast)
                                    .unwrap();
    }

    private static ClusterConfigValue committedConfig(TestKVStore store) {
        return store.get(ClusterConfigKey.CURRENT)
                    .filter(ClusterConfigValue.class::isInstance)
                    .map(ClusterConfigValue.class::cast)
                    .or(() -> fail("No cluster config committed"));
    }

    private static TestKVStore storeWith(ClusterConfigValue committed) {
        var store = new TestKVStore();

        store.seed(ClusterConfigKey.CURRENT, committed);

        return store;
    }

    /// The BootstrapModule self-seed exactly as a formed cluster carries it: no TOML, a core-only
    /// topology under an EMPTY source name (the seed predates any source definition), version 1.
    private static ClusterConfigValue bootstrapSeed() {
        return ClusterConfigValue.clusterConfigValue("",
                                                     "prod",
                                                     "1.0.0",
                                                     List.of(new TopologyEntry("", TopologyEntry.CORE_ROLE, 3)),
                                                     3,
                                                     3,
                                                     "bootstrap-seed",
                                                     1L);
    }

    private static ClusterConfigValue committedOperatorConfig(long configVersion) {
        return ClusterConfigValue.clusterConfigValue(OPERATOR_TOML,
                                                     "prod",
                                                     "1.0.0",
                                                     List.of(CORE_ENTRY, WORKER_ENTRY),
                                                     3,
                                                     3,
                                                     "cloud",
                                                     configVersion);
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

    private static final class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();
        private Option<AetherValue> raceWinner = Option.none();

        private TestKVStore() {
            super(null, null, null);
        }

        void seed(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        /// Model a competing writer that wins the race: our `Put` is discarded and a re-read observes
        /// the competitor's value instead. This is the rejection shape the apply result cannot show —
        /// under batch merging every submitter receives the full merged result list — and the reason
        /// [ClusterConfigRoutes] must confirm a write by re-reading committed state.
        void loseWritesTo(AetherValue winner) {
            raceWinner = Option.some(winner);
        }

        void applyPut(AetherKey key, AetherValue value) {
            raceWinner.onPresent(winner -> storage.put(key, winner));

            if (raceWinner.isEmpty() && isSuccessor(key, value)) {
                storage.put(key, value);
            }
        }

        /// The RFC-0018 successor fence as the real applier enforces it: a `VersionFenced` write lands
        /// only when its version is the IMMEDIATE successor of the committed one, and a first write
        /// against an absent key always lands. Equal versions are rejected — which is exactly what
        /// happened live, where a version-1 initial store met a committed version-1 seed.
        private boolean isSuccessor(AetherKey key, AetherValue value) {
            if (!(storage.get(key) instanceof ClusterConfigValue stored)
                || !(value instanceof ClusterConfigValue incoming)) {
                return true;
            }

            return incoming.configVersion() == stored.configVersion() + 1;
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
