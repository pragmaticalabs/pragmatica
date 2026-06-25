// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.kvstore.AetherKey.*;

import java.util.Set;


@SuppressWarnings("JBCT-UTIL-02")
public sealed interface EphemeralKeys {
    Set<Class<? extends AetherKey>> EPHEMERAL_KEY_TYPES = Set.of(NodeArtifactKey.class,
                                                                 NodeRoutesKey.class,
                                                                 JoinDeadlineKey.class,
                                                                 DrainDeadlineKey.class,
                                                                 EndpointKey.class,
                                                                 ActivationDirectiveKey.class,
                                                                 GovernorAnnouncementKey.class,
                                                                 SliceNodeKey.class,
                                                                 HttpNodeRouteKey.class,
                                                                 SchemaMigrationLockKey.class,
                                                                 StorageStatusKey.class,
                                                                 ConsumerGroupKey.class,
                                                                 DhtPartitionOwnershipKey.class,
                                                                 StreamPartitionOwnershipKey.class,
                                                                 SpokesmanKey.class,
                                                                 ProvisioningSlotKey.class,
                                                                 ClusterPhaseKey.class);

    Set<String> EPHEMERAL_SECTIONS = Set.of("node-artifact",
                                            "node-routes",
                                            "join-deadline",
                                            "drain-deadline",
                                            "endpoints",
                                            "activation",
                                            "governor-announcement",
                                            "slices",
                                            "http-node-routes",
                                            "schema-lock",
                                            "storage-status",
                                            "consumer-group",
                                            "dht-partition-ownership",
                                            "stream-partition-ownership",
                                            "spokesman",
                                            "provisioning-slot",
                                            "cluster-phase");

    static boolean isEphemeral(AetherKey key) {
        return EPHEMERAL_KEY_TYPES.contains(key.getClass());
    }

    static boolean isEphemeralSection(String section) {
        return EPHEMERAL_SECTIONS.contains(section);
    }

    record unused() implements EphemeralKeys {}
}
