// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;


/// Presence-derived core-member descriptor. The synthetic per-node lifecycle enum was removed
/// in the membership-v2 finale: membership is now reconstructible each tick from
/// `presenceSampler.currentMembers()` presence, and the only real node work-state is `NodeReportedState`
/// (SYNCING / READY / DRAINING) reported via pong — carried on the metrics heartbeat, not on
/// this generation-snapshot record. A `CoreMember` therefore carries presence (it exists ⇒ the
/// node is a current member), display address, the SWIM-derived `healthHint`, generation epochs,
/// and the provisioning source.
@Codec public record CoreMember(NodeId nodeId,
                                String host,
                                int port,
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
                                        HealthHint healthHint,
                                        Epoch joinedEpoch,
                                        Epoch lastSeenEpoch) {
        return new CoreMember(nodeId,
                              host,
                              port,
                              healthHint,
                              joinedEpoch,
                              lastSeenEpoch,
                              ProvisioningSource.UNKNOWN);
    }

    public static CoreMember coreMember(NodeId nodeId,
                                        String host,
                                        int port,
                                        HealthHint healthHint,
                                        Epoch joinedEpoch,
                                        Epoch lastSeenEpoch,
                                        ProvisioningSource provisioningSource) {
        return new CoreMember(nodeId, host, port, healthHint, joinedEpoch, lastSeenEpoch, provisioningSource);
    }

    public CoreMember withLastSeenEpoch(Epoch newLastSeenEpoch) {
        return new CoreMember(nodeId,
                              host,
                              port,
                              healthHint,
                              joinedEpoch,
                              newLastSeenEpoch,
                              provisioningSource);
    }

    public CoreMember withHealthHint(HealthHint newHealthHint) {
        return new CoreMember(nodeId,
                              host,
                              port,
                              newHealthHint,
                              joinedEpoch,
                              lastSeenEpoch,
                              provisioningSource);
    }
}
