// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;


/// One core member as observed by the leader at a specific generation epoch.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §6.
@Codec public record CoreMember(NodeId nodeId,
                                String host,
                                int port,
                                NodeLifecycleState lifecycle,
                                HealthHint healthHint,
                                Epoch joinedEpoch,
                                Epoch lastSeenEpoch,
                                ProvisioningSource provisioningSource) {
    public CoreMember {
        if (provisioningSource == null) {provisioningSource = ProvisioningSource.UNKNOWN;}
    }

    public static CoreMember coreMember(NodeId nodeId,
                                        String host,
                                        int port,
                                        NodeLifecycleState lifecycle,
                                        HealthHint healthHint,
                                        Epoch joinedEpoch,
                                        Epoch lastSeenEpoch) {
        return new CoreMember(nodeId,
                              host,
                              port,
                              lifecycle,
                              healthHint,
                              joinedEpoch,
                              lastSeenEpoch,
                              ProvisioningSource.UNKNOWN);
    }

    public static CoreMember coreMember(NodeId nodeId,
                                        String host,
                                        int port,
                                        NodeLifecycleState lifecycle,
                                        HealthHint healthHint,
                                        Epoch joinedEpoch,
                                        Epoch lastSeenEpoch,
                                        ProvisioningSource provisioningSource) {
        return new CoreMember(nodeId, host, port, lifecycle, healthHint, joinedEpoch, lastSeenEpoch, provisioningSource);
    }

    public CoreMember withLastSeenEpoch(Epoch newLastSeenEpoch) {
        return new CoreMember(nodeId,
                              host,
                              port,
                              lifecycle,
                              healthHint,
                              joinedEpoch,
                              newLastSeenEpoch,
                              provisioningSource);
    }

    public CoreMember withHealthHint(HealthHint newHealthHint) {
        return new CoreMember(nodeId,
                              host,
                              port,
                              lifecycle,
                              newHealthHint,
                              joinedEpoch,
                              lastSeenEpoch,
                              provisioningSource);
    }

    public CoreMember withLifecycle(NodeLifecycleState newLifecycle) {
        return new CoreMember(nodeId,
                              host,
                              port,
                              newLifecycle,
                              healthHint,
                              joinedEpoch,
                              lastSeenEpoch,
                              provisioningSource);
    }
}
