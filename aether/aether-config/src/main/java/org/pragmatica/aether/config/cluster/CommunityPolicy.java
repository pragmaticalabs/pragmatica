// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.config.toml.ConfigKeyLive;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// Cluster-wide community sizing and growth policy, committed with the cluster config (`[community_policy]`) so a new leader
/// reads the same numbers the old one did (#241). It replaces the per-node [CommunitySizing] knob as the source of truth;
/// the node-level knob remains only as the fallback for a cluster whose committed config carries no policy.
///
/// - `maxSize`: the per-community worker cap. Stamped as `targetSize` on every community the cluster derives (one per source,
///   `<source>-w-N`); declared `[community.<id>]` communities keep their own `target_size` and are never resized by it.
/// - `viabilityFloor`: the minimum live membership for a community to be ACTIVE, the DHT replication factor.
/// - `maxCommunities`: the per-source bound on derived communities.
/// - `dissolveGraceSeconds`: how long a derived community must stay empty before the leader dissolves it.
/// - `growth`: whether a joiner that finds every derived community of its source full opens a new one. OFF by default; with
///   it off a source keeps its single `<source>-w-0` community exactly as before.
public record CommunityPolicy(int maxSize,
                              int viabilityFloor,
                              @ConfigKeyLive("#241: read by the derived-community growth policy in the change that follows") int maxCommunities,
                              @ConfigKeyLive("#241: read by the derived-community growth policy in the change that follows") int dissolveGraceSeconds,
                              @ConfigKeyLive("#241: read by the derived-community growth policy in the change that follows") boolean growth) {
    /// Hard ceiling on a community's size (worker-membership-spec section 8: the SWIM view of one community stays below it).
    public static final int CEILING = 200;
    public static final int MAX_COMMUNITIES_LIMIT = 100;
    public static final int DEFAULT_MAX_COMMUNITIES = 20;
    public static final int DEFAULT_DISSOLVE_GRACE_SECONDS = 300;
    /// The DHT replication factor: a floor below it weakens redundancy and needs an explicit `allow_below_rf`.
    public static final int REPLICATION_FACTOR = 3;

    private static final Cause GRACE_NEGATIVE = new ClusterConfigError.ParseFailed("community_policy.dissolve_grace_seconds must not be negative");

    /// The policy equivalent of a node-level [CommunitySizing]: growth off, the remaining knobs at their defaults.
    public static CommunityPolicy communityPolicy(CommunitySizing sizing) {
        return new CommunityPolicy(sizing.targetSize(),
                                   sizing.viabilityFloor(),
                                   DEFAULT_MAX_COMMUNITIES,
                                   DEFAULT_DISSOLVE_GRACE_SECONDS,
                                   false);
    }

    public static Result<CommunityPolicy> communityPolicy(int maxSize,
                                                          int viabilityFloor,
                                                          int maxCommunities,
                                                          int dissolveGraceSeconds,
                                                          boolean growth,
                                                          boolean allowBelowRf) {
        return Verify.ensure(viabilityFloor,
                             Verify.Is::between,
                             allowBelowRf
                             ? 1
                             : REPLICATION_FACTOR,
                             CEILING,
                             floor -> floorRefused(floor, allowBelowRf))
                     .flatMap(floor -> Verify.ensure(maxSize,
                                                     Verify.Is::between,
                                                     floor,
                                                     CEILING,
                                                     size -> maxSizeRefused(size, floor)))
                     .flatMap(_ -> Verify.ensure(maxCommunities,
                                                 Verify.Is::between,
                                                 1,
                                                 MAX_COMMUNITIES_LIMIT,
                                                 CommunityPolicy::maxCommunitiesRefused))
                     .flatMap(_ -> Verify.ensure(dissolveGraceSeconds, Verify.Is::nonNegative, GRACE_NEGATIVE))
                     .map(_ -> new CommunityPolicy(maxSize, viabilityFloor, maxCommunities, dissolveGraceSeconds, growth));
    }

    private static Cause floorRefused(int floor, boolean allowBelowRf) {
        return new ClusterConfigError.ParseFailed("community_policy.viability_floor must be at least " + (allowBelowRf
                                                                                                          ? 1
                                                                                                          : REPLICATION_FACTOR)
                                                 + " (the replication factor is " + REPLICATION_FACTOR
                                                 + "; set allow_below_rf = true to lower it), got " + floor);
    }

    private static Cause maxSizeRefused(int size, int floor) {
        return new ClusterConfigError.ParseFailed("community_policy.max_size must be between viability_floor (" + floor
                                                 + ") and " + CEILING
                                                 + ", got " + size);
    }

    private static Cause maxCommunitiesRefused(int count) {
        return new ClusterConfigError.ParseFailed("community_policy.max_communities must be between 1 and " + MAX_COMMUNITIES_LIMIT
                                                 + ", got " + count);
    }
}
