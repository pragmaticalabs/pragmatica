// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.aether.config.cluster.CoreTopology;
import org.pragmatica.aether.config.cluster.InfrastructureConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.OperationsConfig;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.RuntimeProfile;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

class ClusterSecretGenerationTest {

    private static SourceProfile threeCoreCloudSource() {
        return SourceProfile.sourceProfile(sourceNameOrDefault("eu-1"),
                                           SourceType.CLOUD,
                                           Option.some(CloudProviderName.HETZNER),
                                           Option.some("dummy-token"),
                                           Option.some("eu-central"),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(3),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of());
    }

    private static RuntimeProfile defaultRuntime() {
        return RuntimeProfile.runtimeProfile("default",
                                             RuntimeType.CONTAINER,
                                             Option.some("ghcr.io/pragmaticalabs/aether-node:1.0.0"),
                                             Option.empty());
    }

    private static ClusterBootstrapConfig minimalConfig() {
        return ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                             ClusterIdentity.clusterIdentity("prod", "1.0.0").unwrap(),
                                                             CoreTopology.defaultCoreTopology(),
                                                             Map.of("eu-1", threeCoreCloudSource()),
                                                             Map.of("default", defaultRuntime()),
                                                             InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                             OperationsConfig.defaultOperationsConfig());
    }

    @Test
    void buildContext_generatesNonEmptyClusterSecret_inValidatePhase() {
        var result = BootstrapPhaseValidate.execute(minimalConfig());

        var ctx = result.unwrap();
        assertFalse(ctx.clusterSecret().isEmpty(),
                    "Cluster secret MUST be initialized before any phase that needs it (cloud-init writes it into per-node config)");
    }

    @Test
    void buildContext_persistsClusterSecret_inState_forResumeSurvival() {
        var result = BootstrapPhaseValidate.execute(minimalConfig());

        var ctx = result.unwrap();
        assertEquals(ctx.clusterSecret(), ctx.state().clusterSecret(),
                     "Cluster secret must be persisted into BootstrapState so it survives a resume "
                     + "(otherwise resumed nodes get a different secret than the originally provisioned ones)");
    }

    @Test
    void buildContext_generatesIndependentSecrets_acrossInvocations() {
        // Tightening the contract: each fresh bootstrap gets a fresh secret. Two calls must NOT
        // collide (otherwise SecureRandom is broken or someone hardcoded a constant).
        var ctx1 = BootstrapPhaseValidate.execute(minimalConfig()).unwrap();
        var ctx2 = BootstrapPhaseValidate.execute(minimalConfig()).unwrap();

        assertNotEquals(ctx1.clusterSecret(), ctx2.clusterSecret(),
                        "Each bootstrap must generate a unique cluster secret (SecureRandom-derived)");
    }

    @Test
    void bootstrapState_roundTrips_clusterSecretThroughJson() {
        var initial = BootstrapState.initialState(clusterName("prod").unwrap(), "h", "now").withClusterSecret("expected-secret-payload");

        var json = initial.toJson();
        var parsed = BootstrapState.fromJson(json).unwrap();

        assertEquals("expected-secret-payload", parsed.clusterSecret(),
                     "Cluster secret MUST round-trip through JSON serialization for resume");
    }

    @Test
    void bootstrapState_emptySecret_defaultsToEmptyString_whenAbsentFromJson() {
        // Older state files (pre-fix) don't carry a clusterSecret field. Parsing them must
        // not crash; resume code is responsible for regenerating in that case.
        var legacyJson = """
                {
                  "clusterName": "prod",
                  "configHash": "h",
                  "startedAt": "now",
                  "phases": {},
                  "createdResources": [],
                  "provisionedNodeIds": [],
                  "collectedAddresses": []
                }
                """;
        var parsed = BootstrapState.fromJson(legacyJson).unwrap();

        assertTrue(parsed.clusterSecret().isEmpty(),
                   "Legacy state without clusterSecret must parse (empty default), not crash");
    }
}
