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

import org.pragmatica.aether.api.ManagementApiResponses.ApplyConfigRequest;
import org.pragmatica.aether.api.ManagementApiResponses.UpgradeRequest;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.NodeUserDataRenderer;
import org.pragmatica.aether.deployment.cluster.ClusterConfigApplier;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
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
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;


/// #1543 part C: `POST /api/v1/cluster/upgrade` used to store the target in `ClusterConfigValue.version` and leave
/// the committed TOML's `[cluster] version` untouched. Replacements render image tag and jar URL from that TOML, so
/// after an "upgrade" every replacement still booted the OLD version, and a later `apply` of the TOML put the old
/// version back. Every pin goes through the real route handlers and the real renderer.
class ClusterConfigRoutesUpgradeTomlTest {
    private static final String UNPINNED = """
            config_version = "1.0.0"

            [cluster]
            name = "prod"
            version = "1.0.0"

            [runtime.node]
            type = "container"

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

    private static final String JVM_UNPINNED = UNPINNED.replace("type = \"container\"", "type = \"jvm\"");

    private static final String IMAGE_PINNED = UNPINNED.replace("type = \"container\"",
                                                                "type = \"container\"\nimage = \"registry/aether-node:1.0.0\"");

    private static final String JAR_PINNED = JVM_UNPINNED.replace("type = \"jvm\"",
                                                                  "type = \"jvm\"\njar_url = \"https://h/aether-node-1.0.0.jar\"");

    @Test
    void upgrade_rewritesTheCommittedTomlVersion_notOnlyTheStoredCopy() {
        var store = storeWith(committed(UNPINNED, 1));

        assertThat(upgrade(store, "1.1.0", 1).isSuccess()).isTrue();

        var committed = committed(store);

        assertThat(committed.version()).isEqualTo("1.1.0");
        assertThat(tomlVersion(committed)).as("the TOML replacements render from must carry the target").isEqualTo("1.1.0");
        assertThat(committed.configVersion()).isEqualTo(2);
    }

    /// The revert-on-apply half of the defect. `storeUpdatedConfig` stamps the stored version from the applied TOML's
    /// `[cluster] version`, so an apply that reaches the store (here a worker scale, through the real applier) put the
    /// committed TOML's OLD version back over the upgrade.
    @Test
    void apply_afterUpgrade_ofTheCommittedTomlWithAScale_doesNotRevertTheVersion() {
        var store = storeWith(committed(UNPINNED, 1));
        upgrade(store, "1.1.0", 1);
        var upgraded = committed(store);
        var scaled = upgraded.tomlContent().unwrap().replace("count = 2", "count = 4");

        var applied = ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store), permissiveApplier())
                                         .handleApplyConfig(new ApplyConfigRequest(scaled, upgraded.configVersion()))
                                         .await();

        assertThat(applied.isSuccess()).as(String.valueOf(applied)).isTrue();
        assertThat(committed(store).desiredCountFor("hetzner", "worker")).as("the apply must have landed").isEqualTo(4);
        assertThat(committed(store).version()).isEqualTo("1.1.0");
        assertThat(tomlVersion(committed(store))).isEqualTo("1.1.0");
    }

    private static ClusterConfigApplier permissiveApplier() {
        return ClusterConfigApplier.clusterConfigApplier((ClusterTopologyManager) Proxy.newProxyInstance(ClusterTopologyManager.class.getClassLoader(),
                                                                                                         new Class[]{ClusterTopologyManager.class},
                                                                                                         (_, method, _) -> method.getName().equals("usesExplicitCommunities")
                                                                                                                           ? false
                                                                                                                           : null));
    }

    /// The CTM render path: the committed TOML is parsed and rendered by [NodeUserDataRenderer]. Before the fix the
    /// rendered image tag was the old version after a successful upgrade.
    @Test
    void replacementRenderedFromTheCommittedToml_afterUpgrade_bootsTheTargetImage() {
        var store = storeWith(committed(UNPINNED, 1));
        upgrade(store, "1.1.0", 1);

        var script = render(committed(store));

        assertThat(script).contains("aether-node:1.1.0").doesNotContain("aether-node:1.0.0");
    }

    @Test
    void replacementRenderedFromTheCommittedToml_afterUpgrade_bootsTheTargetJar() {
        var store = storeWith(committed(JVM_UNPINNED, 1));
        upgrade(store, "1.1.0", 1);

        assertThat(render(committed(store))).contains("/releases/download/v1.1.0/aether-node.jar");
    }

    /// A pin written with `{version}` follows the upgrade: it is accepted, and the replacement renders the target.
    @Test
    void upgrade_whenThePinCarriesThePlaceholder_succeeds_andTheReplacementRendersTheTarget() {
        var toml = IMAGE_PINNED.replace("aether-node:1.0.0", "aether-node:{version}");
        var store = storeWith(committed(toml, 1));

        assertThat(upgrade(store, "1.1.0", 1).isSuccess()).isTrue();
        assertThat(render(committed(store))).contains("registry/aether-node:1.1.0");
    }

    @Test
    void upgrade_whenTheJarPinCarriesThePlaceholder_succeeds_andTheReplacementRendersTheTargetJar() {
        var toml = JAR_PINNED.replace("aether-node-1.0.0.jar", "v{version}/aether-node.jar");
        var store = storeWith(committed(toml, 1));

        assertThat(upgrade(store, "1.1.0", 1).isSuccess()).isTrue();
        assertThat(render(committed(store))).contains("https://h/v1.1.0/aether-node.jar").doesNotContain("{version}");
    }

    @Test
    void upgrade_whenARoleProfilePinsTheImage_isRefusedWith409_andWritesNothing() {
        var store = storeWith(committed(IMAGE_PINNED, 1));
        var result = upgrade(store, "1.1.0", 1);

        assertRefusedAsPinned(result, "node");
        assertUnchanged(store, IMAGE_PINNED);
    }

    @Test
    void upgrade_whenARoleProfilePinsTheJarUrl_isRefusedWith409_andWritesNothing() {
        var store = storeWith(committed(JAR_PINNED, 1));
        var result = upgrade(store, "1.1.0", 1);

        assertRefusedAsPinned(result, "node");
        assertUnchanged(store, JAR_PINNED);
    }

    /// A seed carries no TOML and renders no replacement user data, so only the stored version moves (the behaviour
    /// the fence tests rely on).
    @Test
    void upgrade_ofABootstrapSeedWithNoToml_stillMovesTheStoredVersion() {
        var store = storeWith(ClusterConfigValue.bootstrapSeed("prod", "1.0.0", List.of(new TopologyEntry("", "core", 3)), 3, 3, "bootstrap-seed", 1L));

        assertThat(upgrade(store, "1.1.0", 1).isSuccess()).isTrue();
        assertThat(committed(store).version()).isEqualTo("1.1.0");
        assertThat(committed(store).tomlContent().isEmpty()).isTrue();
    }

    private static void assertRefusedAsPinned(org.pragmatica.lang.Result<?> result, String profile) {
        assertThat(result.isFailure()).as("an upgrade the pin would silently ignore must be refused, got: " + result).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ClusterConfigError.UpgradeVersionPinned.class);
            assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(cause.message()).contains(profile);
        });
    }

    private static void assertUnchanged(TestKVStore store, String toml) {
        var committed = committed(store);

        assertThat(committed.configVersion()).as("no write may land behind a refusal").isEqualTo(1);
        assertThat(committed.version()).isEqualTo("1.0.0");
        assertThat(committed.tomlContent()).isEqualTo(Option.some(toml));
    }

    private static String render(ClusterConfigValue config) {
        var parsed = ClusterBootstrapConfigParser.parse(config.tomlContent().unwrap()).unwrap();

        return NodeUserDataRenderer.render(parsed,
                                           parsed.sources().get("hetzner"),
                                           NodeRole.CORE,
                                           "hetzner-core-9",
                                           0,
                                           "secret",
                                           clusterName("prod").unwrap(),
                                           TomlDocument.EMPTY,
                                           List.of(),
                                           List.of());
    }

    private static String tomlVersion(ClusterConfigValue config) {
        return ClusterBootstrapConfigParser.parse(config.tomlContent().unwrap()).unwrap().cluster().version();
    }

    private static org.pragmatica.lang.Result<org.pragmatica.aether.api.ManagementApiResponses.UpgradeResponse> upgrade(TestKVStore store,
                                                                                                                         String target,
                                                                                                                         long expected) {
        return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store))
                                  .handleUpgrade(new UpgradeRequest(target, expected))
                                  .await();
    }

    private static ClusterConfigValue committed(TestKVStore store) {
        return store.get(ClusterConfigKey.CURRENT).map(ClusterConfigValue.class::cast).unwrap();
    }

    private static ClusterConfigValue committed(String toml, long configVersion) {
        return ClusterConfigValue.clusterConfigValue(toml,
                                                     "prod",
                                                     "1.0.0",
                                                     List.of(new TopologyEntry("hetzner", "core", 3), new TopologyEntry("hetzner", "worker", 2)),
                                                     3,
                                                     9,
                                                     "cloud",
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
