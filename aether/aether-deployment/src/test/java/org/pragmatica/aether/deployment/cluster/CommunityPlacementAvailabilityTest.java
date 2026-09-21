// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.lang.Option;
import static org.assertj.core.api.Assertions.assertThat;

class CommunityPlacementAvailabilityTest {
    private static final CommunityPlacement.Location PREFERRED = new CommunityPlacement.Location("preferred", Option.some("east"), 2, 9);
    private static final CommunityPlacement.Location ALTERNATE = new CommunityPlacement.Location("alternate", Option.some("west"), 1, 1);

    @Test void unavailablePreferenceRedistributesOnlyDiscretionaryCapacity() {
        var policy = new CommunityPlacement("stable", 10, List.of(PREFERRED, ALTERNATE));
        var counts = CommunityPlacementAvailability.effectiveCounts(policy, Set.of(PREFERRED));
        assertThat(counts.get(PREFERRED)).isEqualTo(2);
        assertThat(counts.get(ALTERNATE)).isEqualTo(8);
        assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
    }

    @Test void allUnavailableRetainsDesiredObligations_withoutInventingEligibleCapacity() {
        var policy = new CommunityPlacement("stable", 10, List.of(PREFERRED, ALTERNATE));
        assertThat(CommunityPlacementAvailability.effectiveCounts(policy, Set.of(PREFERRED, ALTERNATE))).isEqualTo(policy.desiredCounts());
    }

    @Test void restoredPreferenceConvergesToOriginalExactCounts_andPolicyIdentityIsOrderIndependent() {
        var policy = new CommunityPlacement("stable", 10, List.of(PREFERRED, ALTERNATE));
        var reversed = new CommunityPlacement("stable", 10, List.of(ALTERNATE, PREFERRED));
        assertThat(CommunityPlacementAvailability.effectiveCounts(policy, Set.of())).isEqualTo(policy.desiredCounts());
        assertThat(CommunityPlacementAvailability.policyIdentity(policy)).isEqualTo(CommunityPlacementAvailability.policyIdentity(reversed));
        assertThat(CommunityPlacementAvailability.policyIdentity(policy)).isNotEqualTo(CommunityPlacementAvailability.policyIdentity(new CommunityPlacement("stable", 11, policy.locations())));
    }

    @Test void retryDelayIsBoundedExponential() {
        assertThat(CommunityPlacementAvailability.retryDelay(1).millis()).isEqualTo(30000);
        assertThat(CommunityPlacementAvailability.retryDelay(2).millis()).isEqualTo(60000);
        assertThat(CommunityPlacementAvailability.retryDelay(31).millis()).isEqualTo(600000);
    }
}
