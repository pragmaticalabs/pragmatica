package org.pragmatica.aether.environment;

import java.util.Set;


/// Placement constraints for node provisioning.
/// Used by CTM to request specific placement from ComputeProvider.
public sealed interface PlacementHint {
    record ZoneHint(String zoneName) implements PlacementHint{}

    record HostGroupHint(String groupId) implements PlacementHint{}

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
