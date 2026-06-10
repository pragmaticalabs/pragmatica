// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.Set;


public sealed interface PlacementHint {
    record ZoneHint(String zoneName) implements PlacementHint {}

    record HostGroupHint(String groupId) implements PlacementHint {}

    record AffinityHint(Set<String> preferredZones) implements PlacementHint {
        public AffinityHint {
            preferredZones = Set.copyOf(preferredZones);
        }
    }

    record AntiAffinityHint(Set<String> avoidZones) implements PlacementHint {
        public AntiAffinityHint {
            avoidZones = Set.copyOf(avoidZones);
        }
    }

    static ZoneHint zoneHint(String zoneName) {
        return new ZoneHint(zoneName);
    }

    static HostGroupHint hostGroupHint(String groupId) {
        return new HostGroupHint(groupId);
    }

    static AffinityHint affinityHint(Set<String> preferredZones) {
        return new AffinityHint(preferredZones);
    }

    static AntiAffinityHint antiAffinityHint(Set<String> avoidZones) {
        return new AntiAffinityHint(avoidZones);
    }
}
