// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.worker.health;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityHealthMessageCodecTest {
    @Test
    void challengedReportPreservesTypedObservationAgeThroughProductionRegistry() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var member = new CommunityHealthMessage.MemberHealth(new NodeId("worker"), 7, true, true,
                                                              TimeSpan.timeSpan(123456789).nanos());
        var report = new CommunityHealthMessage.Report(new NodeId("governor"), "community", 3,
                                                       "core-incarnation", 11, List.of(member));
        CommunityHealthMessage.Report decoded = codec.decode(codec.encode(report));

        assertThat(decoded).isEqualTo(report);
        assertThat(decoded.members().getFirst().observationAge()).isEqualTo(member.observationAge());
    }
}
