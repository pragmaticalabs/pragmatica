package org.pragmatica.aether.environment;

import java.util.Set;


/// Placement constraints for node provisioning.
/// Used by CTM to request specific placement from ComputeProvider.
public sealed interface PlacementHint {
    /// Prefer provisioning in the specified availability zone.
    record ZoneHint(String zoneName) implements PlacementHint {}

    /// Prefer provisioning on the specified rack/host group.
    record HostGroupHint(String groupId) implements PlacementHint {}

    /// Prefer zones/hosts that already have nodes from the given set (co-location).
    record AffinityHint(Set<String> preferredZones) implements PlacementHint {
        public AffinityHint {
            preferredZones = Set.copyOf(preferredZones);
        }
    }

    /// Avoid zones/hosts that have nodes from the given set (spread).
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
