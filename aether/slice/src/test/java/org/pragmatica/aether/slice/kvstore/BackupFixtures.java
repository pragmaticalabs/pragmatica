// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.ArtifactCodecsSlice;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.SliceCodecsSlice;
import org.pragmatica.aether.slice.SliceCodecsSliceApi;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.blueprint.BlueprintCodecsSlice;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.delegation.DelegationCodecsSlice;
import org.pragmatica.aether.slice.generation.GenerationCodecsSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AlertThresholdKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyAuditKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AutoHealStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.LogLevelKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ObservabilityConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AlertThresholdValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyAuditValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AutoHealStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue.NamedAddress;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.LogLevelValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ObservabilityConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceCodecsSliceApi;
import org.pragmatica.aether.slice.stream.StreamCodecsSliceApi;
import org.pragmatica.cluster.state.kvstore.KvstoreCodecs;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;

/// Shared fixtures for the backup codec and the backup classification guard.
///
/// [#FIXTURES] holds at least one (key, value) pair for EVERY backed-up key type — the round-trip test
/// fails naming any [ClusterStateKey] type that has none, so a new cluster-state key cannot join the
/// backup without being exercised through it. Several values deliberately carry the characters the old
/// pipe grammar broke on (`|`, `=`, quotes, newlines, backslashes, non-ASCII) so a regression to any
/// text-level encoding would show.
final class BackupFixtures {
    static final String AWKWARD = "a|b=c \"quoted\" 'single'\nsecond line\r\\back\\slash ünïcødé 日本 🚀";

    static final List<Fixture> FIXTURES = fixtures();

    private BackupFixtures() {}

    record Fixture(ClusterStateKey key, AetherValue value) {}

    /// The codec registries a node assembles for the KV value types and the KV commands carrying them,
    /// minus everything unrelated.
    static SliceCodec codec() {
        var codecs = new ArrayList<TypeCodec<?>>();

        codecs.addAll(ArtifactCodecsSlice.CODECS);
        codecs.addAll(SliceCodecsSlice.CODECS);
        codecs.addAll(SliceCodecsSliceApi.CODECS);
        codecs.addAll(StreamCodecsSliceApi.CODECS);
        codecs.addAll(ResourceCodecsSliceApi.CODECS);
        codecs.addAll(DelegationCodecsSlice.CODECS);
        codecs.addAll(GenerationCodecsSlice.CODECS);
        codecs.addAll(BlueprintCodecsSlice.CODECS);
        codecs.addAll(KvstoreCodecsSlice.CODECS);
        codecs.addAll(KvstoreCodecs.CODECS);

        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), codecs);
    }

    static ExpandedBlueprint blueprint() {
        var id = BlueprintId.blueprintId("org.example:orders-app:1.2.3").unwrap();
        var slice = ResolvedSlice.resolvedSlice(artifact("org.example:orders-slice:1.2.3"),
                                                3,
                                                2,
                                                false,
                                                Set.of(artifact("org.example:inventory-slice:2.0.0")),
                                                Option.some(7),
                                                Option.some(0.8),
                                                Option.some(0.2))
                                 .unwrap();
        var dependency = ResolvedSlice.resolvedSlice(artifact("org.example:inventory-slice:2.0.0"), 1, true)
                                      .unwrap();
        var overrides = SecurityOverrides.securityOverrides(List.of(SecurityOverrides.Entry.entry("POST /orders/**",
                                                                                                  "AUTHENTICATED")),
                                                            SecurityOverridePolicy.FULL);

        return ExpandedBlueprint.expandedBlueprint(id,
                                                   List.of(dependency, slice),
                                                   Option.some("[database.orders]\nurl = \"jdbc:x|y=z\"\n# ünïcødé\n"),
                                                   overrides);
    }

    private static List<Fixture> fixtures() {
        var owner = BlueprintId.blueprintId("org.example:orders-app:1.2.3").unwrap();
        var base = ArtifactBase.artifactBase("org.example:orders-slice").unwrap();
        var v1 = Version.version("1.2.3").unwrap();
        var v2 = Version.version("1.3.0").unwrap();
        var topic = ResourceAddress.resourceAddress("io.acme.inventory:stock-updates:2.0.0").unwrap();

        return List.of(new Fixture(SliceTargetKey.sliceTargetKey(base),
                                   SliceTargetValue.sliceTargetValue(v1, 3, Option.some(owner))),
                       new Fixture(AppBlueprintKey.appBlueprintKey(owner), AppBlueprintValue.appBlueprintValue(blueprint(), true)),
                       new Fixture(DeploymentOutcomeKey.deploymentOutcomeKey(owner),
                                   DeploymentOutcomeValue.failed(List.of("org.example:orders-slice:1.2.3", AWKWARD), AWKWARD, 1_700_000_000_000L)),
                       new Fixture(VersionRoutingKey.versionRoutingKey(base), VersionRoutingValue.versionRoutingValue(v1, v2)),
                       new Fixture(DeploymentKey.deploymentKey("deploy-42"),
                                   DeploymentValue.deploymentValue("deploy-42",
                                                                   owner.asString(),
                                                                   "1.2.3",
                                                                   "1.3.0",
                                                                   "CANARY",
                                                                   "ROUTING",
                                                                   "1:4",
                                                                   "stages=10,50,100|pause=30s",
                                                                   "errorRate=0.01;latencyP99=250",
                                                                   "KEEP_OLD",
                                                                   "org.example:orders-slice,org.example:inventory-slice",
                                                                   2,
                                                                   1_700_000_000_000L,
                                                                   1_700_000_100_000L)),
                       new Fixture(PreviousVersionKey.previousVersionKey(base), PreviousVersionValue.previousVersionValue(base, v1, v2)),
                       new Fixture(LogLevelKey.forLogger("org.example.Orders$Inner"), LogLevelValue.logLevelValue("org.example.Orders$Inner", "DEBUG")),
                       new Fixture(ObservabilityConfigKey.observabilityConfigKey("org.example:orders-slice", "placeOrder"),
                                   ObservabilityConfigValue.observabilityConfigValue("org.example:orders-slice", "placeOrder", true, false, true, false, 3)),
                       new Fixture(AlertThresholdKey.alertThresholdKey("alert-threshold/cpu.usage").unwrap(),
                                   AlertThresholdValue.alertThresholdValue("cpu.usage", 0.75, 0.95)),
                       new Fixture(ConfigKey.forKey("orders.banner"), ConfigValue.configValue("orders.banner", AWKWARD)),
                       new Fixture(ConfigKey.forKey("key with spaces | = \"quotes\" ünïcødé\nand a newline\\"),
                                   ConfigValue.configValue("key with spaces | = \"quotes\" ünïcødé\nand a newline\\", "")),
                       new Fixture(SchemaVersionKey.schemaVersionKey("database.orders"),
                                   SchemaVersionValue.schemaVersionValue("database.orders", 3, "V003__add_index.sql", SchemaStatus.FAILED, owner.asString(), owner, 2)),
                       new Fixture(CommunityKey.communityKey("eu-west/workers"), CommunityValue.communityValue("eu-west", "worker", 12)),
                       new Fixture(AbTestKey.abTestKey("ab-test/checkout-v2").unwrap(),
                                   AbTestValue.abTestValue("checkout-v2",
                                                           base,
                                                           v1,
                                                           "{\"b\":\"1.3.0\",\"c\":\"1.4.0\"}",
                                                           "RUNNING",
                                                           "{\"header\":\"X-Cohort\",\"split\":\"50|50\"}",
                                                           50,
                                                           50,
                                                           owner.asString(),
                                                           1_700_000_000_000L,
                                                           1_700_000_000_500L)),
                       new Fixture(ClusterConfigKey.CURRENT,
                                   ClusterConfigValue.clusterConfigValue("[cluster]\nname = \"prod|eu\"\n# ünïcødé = yes\n",
                                                                         "prod",
                                                                         "1.0.0-rc4",
                                                                         List.of(new TopologyEntry("eu", "core", 5),
                                                                                 new TopologyEntry("us", "worker", 4)),
                                                                         3,
                                                                         9,
                                                                         "hetzner",
                                                                         7)),
                       new Fixture(StreamConfigKey.streamConfigKey("orders.events"),
                                   StreamConfigValue.streamConfigValue(new StreamConfig("orders.events",
                                                                                        8,
                                                                                        RetentionPolicy.retentionPolicy(1_000, 1_048_576, 604_800_000, RetentionMode.ANY),
                                                                                        "earliest",
                                                                                        65_536,
                                                                                        ConsistencyMode.STRONG,
                                                                                        3,
                                                                                        2,
                                                                                        StreamCompression.ZSTD,
                                                                                        Option.some("kms-key-1")),
                                                                       1_700_000_000_000L)),
                       new Fixture(ApiKeyKey.apiKeyKey("key-1"), ApiKeyValue.apiKeyValue("key-1", "sha256:abc=", 60_000)),
                       new Fixture(ApiKeyAuditKey.apiKeyAuditKey("audit-1"), ApiKeyAuditValue.apiKeyAuditValue("key-1", "REVOKED", AWKWARD)),
                       new Fixture(AutoHealStateKey.autoHealStateKey(), AutoHealStateValue.autoHealStateValue(false, AWKWARD, 1_700_000_000_000L)),
                       new Fixture(BlueprintStreamBindingsKey.blueprintStreamBindingsKey(owner),
                                   BlueprintStreamBindingsValue.blueprintStreamBindingsValue(List.of(NamedAddress.namedAddress("stock", topic)))),
                       new Fixture(EntityCheckpointKey.entityCheckpointKey("orders/by-customer", 5),
                                   EntityFoldCheckpointValue.entityFoldCheckpointValue(4_242L, "00ff10ab")));
    }

    private static Artifact artifact(String coordinates) {
        return Artifact.artifact(coordinates)
                       .unwrap();
    }
}
