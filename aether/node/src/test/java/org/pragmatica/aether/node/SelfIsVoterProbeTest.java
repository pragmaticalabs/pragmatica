package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #2306: the roster probe that decides whether a PASSIVE on this node may be called a quorum loss. Unknown counts as a
/// voter, so a cluster that never formed still reports its quorum loss; only an installed roster that excludes the node
/// makes it an observer.
class SelfIsVoterProbeTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId OTHER = new NodeId("node-2");

    @Test
    void isInstalledVoterOrUnknown_isTrue_whenNoRosterIsInstalled() {
        assertThat(AetherNode.isInstalledVoterOrUnknown(Option.none(), SELF)).isTrue();
    }

    @Test
    void isInstalledVoterOrUnknown_isTrue_whenTheRosterContainsSelf() {
        var roster = new VoterConfiguration(0, new ClusterConfig(List.of(SELF, OTHER)));

        assertThat(AetherNode.isInstalledVoterOrUnknown(Option.some(roster), SELF)).isTrue();
    }

    @Test
    void isInstalledVoterOrUnknown_isFalse_whenTheInstalledRosterExcludesSelf() {
        var roster = new VoterConfiguration(0, new ClusterConfig(List.of(OTHER)));

        assertThat(AetherNode.isInstalledVoterOrUnknown(Option.some(roster), SELF)).isFalse();
    }
}
