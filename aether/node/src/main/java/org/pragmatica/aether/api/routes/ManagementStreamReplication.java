// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.deployment.cluster.ClusterReplication;
import org.pragmatica.aether.slice.ReplicationDeclaration;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Result;


/// #1564: a stream the management API mints carries no declaration, so it takes the cluster's replication defaults —
/// the empty declaration resolved against the committed `[replication]` section and desired core count, through the
/// same [ReplicationDeclaration] path every declared resource uses.
sealed interface ManagementStreamReplication {
    static Result<StreamConfig> withClusterDefaults(KVStore<AetherKey, AetherValue> kvStore, StreamConfig config) {
        return ClusterReplication.context(kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                           AetherValue.ClusterConfigValue.class))
                                 .flatMap(context -> context.resolve(ReplicationDeclaration.NONE))
                                 .map(resolved -> config.withReplication(resolved.factors()));
    }

    record unused() implements ManagementStreamReplication {}
}
