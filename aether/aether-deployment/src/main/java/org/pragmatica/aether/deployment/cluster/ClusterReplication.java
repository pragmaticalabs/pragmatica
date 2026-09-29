// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.function.Supplier;

import org.pragmatica.aether.config.ReplicationDefaultsConfig;
import org.pragmatica.aether.config.cluster.ReplicationDefaultsParser;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// #1564: the committed cluster config (`ClusterConfigKey.CURRENT`) read as replication state — the `[replication]`
/// defaults of its TOML ([ReplicationDefaultsParser]) and its DESIRED core count ([ClusterConfigValue#coreCount]).
/// The one conversion both deploy validation and the node's resource provisioning use, so a declaration resolves
/// the same way at deploy and at activation. No committed config yet means the built-in defaults and no core count.
public sealed interface ClusterReplication {
    /// The `system:cluster-events` factors with no committed cluster config: the built-in RF 3 and the built-in
    /// `[replication.cluster_events]` CF.
    ReplicationFactors CLUSTER_EVENTS_BUILT_IN = new ReplicationFactors(ReplicationFactors.BUILT_IN.replicationFactor(),
                                                                        ReplicationDefaultsConfig.BUILT_IN.clusterEventsConfirmationFactor());

    static Result<ReplicationContext> context(Option<ClusterConfigValue> committed) {
        return committed.fold(() -> Result.success(ReplicationContext.BUILT_IN), ClusterReplication::contextOf);
    }

    /// A node-side [ReplicationContext.Source] over the committed cluster config, read on every resolution.
    static ReplicationContext.Source source(Supplier<Option<ClusterConfigValue>> committed) {
        return () -> context(committed.get());
    }

    /// The `system:cluster-events` stream's factors: CF from `[replication.cluster_events]` (default 1, pending the
    /// owner's acked-but-lost decision), RF the desired core count — the stream's placement RF is the cluster size
    /// anyway ([org.pragmatica.aether.stream.replication.ReplicaPlacement]), so recording it lets the engine check
    /// `CF <= RF` meaningfully. Without a committed core count RF is the built-in 3.
    static Result<ReplicationFactors> clusterEventsFactors(Option<ClusterConfigValue> committed) {
        return defaults(committed).flatMap(defaults -> clusterEventsFactors(committed, defaults));
    }

    /// #1564 (B1): a cluster config is admissible only if the `system:cluster-events` factors it implies resolve —
    /// `[replication.cluster_events] confirmation_factor` at most the DESIRED core count, which is that stream's RF
    /// ([#clusterEventsFactors]). Checked by both writers of the desired core count, the config apply and a core
    /// scale, BEFORE the commit: a committed config the registrar cannot satisfy would leave cluster-events
    /// uncommitted with nothing to retry into success.
    static Result<ClusterConfigValue> admissible(ClusterConfigValue value) {
        var committed = Option.some(value);

        return defaults(committed).flatMap(defaults -> clusterEventsFactors(committed, defaults).mapError(cause -> new ClusterEventsFactorsRefused(value.coreCount(),
                                                                                                                                                   cause)))
                       .map(_ -> value);
    }

    /// The typed refusal of an inadmissible cluster config (see [#admissible]).
    record ClusterEventsFactorsRefused(int desiredCoreCount, Cause cause) implements Cause {
        @Override
        public String message() {
            return "[replication.cluster_events] confirmation_factor must not exceed the desired core count (" + desiredCoreCount
                 + "), which is system:cluster-events' replication_factor: " + cause.message();
        }
    }

    private static Result<ReplicationFactors> clusterEventsFactors(Option<ClusterConfigValue> committed,
                                                                   ReplicationDefaultsConfig defaults) {
        var cores = committed.map(ClusterConfigValue::coreCount)
                             .filter(count -> count > 0)
                             .or(ReplicationFactors.BUILT_IN.replicationFactor());

        return ReplicationFactors.replicationFactors(cores, defaults.clusterEventsConfirmationFactor());
    }

    private static Result<ReplicationContext> contextOf(ClusterConfigValue value) {
        return ReplicationDefaultsParser.fromClusterToml(value.tomlContent())
                                        .flatMap(defaults -> ReplicationFactors.replicationFactors(defaults.replicationFactor(),
                                                                                                   defaults.confirmationFactor()))
                                        .map(factors -> ReplicationContext.replicationContext(factors,
                                                                                              value.coreCount()));
    }

    private static Result<ReplicationDefaultsConfig> defaults(Option<ClusterConfigValue> committed) {
        return committed.fold(() -> Result.success(ReplicationDefaultsConfig.BUILT_IN),
                              value -> ReplicationDefaultsParser.fromClusterToml(value.tomlContent()));
    }

    record unused() implements ClusterReplication {}
}
