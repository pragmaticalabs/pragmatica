// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.CommunityPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;


/// The community policy a leader acts on (#241): the committed `[community_policy]` when the cluster config carries one,
/// otherwise the node-level [CommunitySizing] with growth off. Reading it from the committed config is what makes a leader
/// failover to a differently configured node change nothing about the floor or the target of an existing community.
///
/// The cluster-config TOML is parsed once per distinct text, not once per read: the view is consulted on every worker join
/// and every community evaluation.
public final class CommunityPolicyView {
    private record Parsed(String toml, Option<CommunityPolicy> policy) {
        static Parsed parsed(String toml, Option<CommunityPolicy> policy) {
            return new Parsed(toml, policy);
        }
    }

    private final KVStore<AetherKey, AetherValue> store;
    private final CommunityPolicy fallback;

    private final AtomicReference<Parsed> parsed = new AtomicReference<>(Parsed.parsed("", Option.none()));

    private CommunityPolicyView(KVStore<AetherKey, AetherValue> store, CommunityPolicy fallback) {
        this.store = store;
        this.fallback = fallback;
    }

    public static CommunityPolicyView communityPolicyView(KVStore<AetherKey, AetherValue> store,
                                                          CommunitySizing sizing) {
        return new CommunityPolicyView(store, CommunityPolicy.communityPolicy(sizing));
    }

    public CommunityPolicy current() {
        return committed().or(fallback);
    }

    /// The policy committed in the cluster config, none when the config carries no `[community_policy]`.
    public Option<CommunityPolicy> committed() {
        return store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class)
                    .flatMap(AetherValue.ClusterConfigValue::tomlContent)
                    .flatMap(this::policyOf);
    }

    private Option<CommunityPolicy> policyOf(String toml) {
        var cached = parsed.get();

        return cached.toml()
                     .equals(toml)
               ? cached.policy()
               : parse(toml);
    }

    // FER (degrade forward): a committed config that no longer parses was validated when it was applied, so this is a
    // format drift between versions. The leader keeps acting on the node-level sizing instead of stopping community
    // evaluation. Guarantee: the leader never halts on it; the config route refuses the same text at apply time.
    // Mechanism: the unparseable text is cached as "no policy", so it is not re-parsed on every join.
    private Option<CommunityPolicy> parse(String toml) {
        var policy = ClusterBootstrapConfigParser.parse(toml).option().flatMap(ClusterBootstrapConfig::communityPolicy);

        parsed.set(Parsed.parsed(toml, policy));

        return policy;
    }
}
