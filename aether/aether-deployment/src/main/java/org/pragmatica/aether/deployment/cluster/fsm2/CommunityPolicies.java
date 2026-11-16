// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.config.cluster.CommunityPolicy;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.lang.Option;


/// The community policy the leader acts on (R-CF.16..18, #241, legacy `CommunityPolicyView`): the `[community_policy]` of the
/// COMMITTED cluster config when it carries one, otherwise the node-level sizing (`Settings`) with growth off. Reading it from the
/// committed config is what keeps a failover to a differently configured leader from changing the target or the floor of an existing
/// community.
public sealed interface CommunityPolicies {
    /// The committed policy, else the node-level fallback.
    static CommunityPolicy current(ClusterModel m, Ctx ctx) {
        return m.communityPolicy.or(() -> fallback(ctx.settings()));
    }

    static CommunityPolicy fallback(Settings settings) {
        return CommunityPolicy.communityPolicy(CommunitySizing.communitySizing(settings.communityTargetSize(),
                                                                               settings.communityViabilityFloor()));
    }

    // FER (degrade forward), as the legacy view: a committed config that no longer parses was validated when it was applied, so this is a
    // format drift between versions. Guarantee: the leader never halts on it and acts on the node-level sizing. Mechanism: an unparsable
    // text reads as "no policy", and the result is cached on the model by the indexer, so it is parsed once per committed config.
    /// The `[community_policy]` of a committed cluster config, none when it carries none or does not parse.
    static Option<CommunityPolicy> committed(ClusterConfigValue value) {
        return value.tomlContent()
                    .flatMap(CommunityPolicies::parsed);
    }

    private static Option<CommunityPolicy> parsed(String toml) {
        return parsedConfig(toml).flatMap(ClusterBootstrapConfig::communityPolicy);
    }

    // FER (degrade forward), as `committed` above: a committed config that no longer parses reads as "no placement policy", so a worker
    // takes a derived community. Guarantee: the leader never halts on it. The legacy engine instead leaves the worker without a directive
    // and logs (ClusterDeploymentState.assignWorkerRole); both are a format drift between versions of a config validated when applied.
    /// The `[community.<id>]` placement policies of a committed cluster config (B-070), empty when it carries none or does not parse.
    /// Indexed with the config row, so the worker community choice (`Directory.place`) reads the committed row in commit order: the policy
    /// commit precedes every placement operation the reconciler derives from it, and so every node that operation provisions.
    static Map<String, CommunityPlacement> placements(ClusterConfigValue value) {
        return value.tomlContent()
                    .flatMap(CommunityPolicies::parsedConfig)
                    .map(ClusterBootstrapConfig::communities)
                    .or(Map.of());
    }

    private static Option<ClusterBootstrapConfig> parsedConfig(String toml) {
        return ClusterBootstrapConfigParser.parse(toml)
                                           .option();
    }

    record unused() implements CommunityPolicies {}
}
