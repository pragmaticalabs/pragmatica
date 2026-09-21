// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CommunityPoliciesTest {
    private static final String CONFIG = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.pool]
        type = "forge"
        [source.pool.core]
        count = 3
        [source.pool.worker]
        count = 1
        """;

    @Test
    void implicitTargetUsesCommittedScalingIntentAndStableCommunityIdentity() {
        var config = ClusterBootstrapConfigParser.parse(CONFIG).unwrap();
        var intent = new AetherValue.ClusterConfigValue(CONFIG, "test", "1.0.0",
            List.of(new AetherValue.TopologyEntry("pool", "worker", 7)), 3, 3, "forge", 2, 0);
        var normalized = CommunityPolicies.normalize(config, intent).unwrap();
        assertThat(normalized.communities()).containsOnlyKeys("pool-w-0");
        assertThat(normalized.communities().get("pool-w-0").targetSize()).isEqualTo(7);
        assertThat(normalized.communities().get("pool-w-0").locations().getFirst().source()).isEqualTo("pool");
    }

    @Test
    void invalidWorkerTargetIsRejectedAtPolicyConstruction() {
        var config = ClusterBootstrapConfigParser.parse(CONFIG).unwrap();
        var intent = new AetherValue.ClusterConfigValue(CONFIG, "test", "1.0.0",
            List.of(new AetherValue.TopologyEntry("pool", "worker", -1)), 3, 3, "forge", 2, 0);
        assertThat(CommunityPolicies.normalize(config, intent).isFailure()).isTrue();
    }

    @Test
    void explicitPolicyIsNotOverwrittenBySourceHeadcount() {
        var explicit = CONFIG + """
            [community.regional]
            target_size = 4
            [community.regional.placement.home]
            source = "pool"
            """;
        var config = ClusterBootstrapConfigParser.parse(explicit).unwrap();
        var intent = new AetherValue.ClusterConfigValue(explicit, "test", "1.0.0",
            List.of(new AetherValue.TopologyEntry("pool", "worker", 7)), 3, 3, "forge", 2, 0);
        assertThat(CommunityPolicies.normalize(config, intent).unwrap().communities().get("regional").targetSize()).isEqualTo(4);
    }
}
