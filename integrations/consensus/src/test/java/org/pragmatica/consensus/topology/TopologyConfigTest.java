package org.pragmatica.consensus.topology;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class TopologyConfigTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeInfo INFO_1 = NodeInfo.nodeInfo(NODE_1, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    private static final NodeInfo INFO_2 = NodeInfo.nodeInfo(NODE_2, NodeAddress.nodeAddress("localhost", 5001).unwrap());

    @Nested
    class CoreLimits {

        @Test
        void convenienceConstructor_defaultsCoreMaxToZero() {
            var config = new TopologyConfig(NODE_1, 2, timeSpan(1).seconds(), timeSpan(1).seconds(), List.of(INFO_1, INFO_2));

            assertThat(config.coreMax()).isZero();
            assertThat(config.coreMin()).isEqualTo(2);
        }

        @Test
        void canonicalConstructor_preservesCoreMaxAndCoreMin() {
            var config = new TopologyConfig(NODE_1, 3, timeSpan(1).seconds(), timeSpan(1).seconds(),
                                            timeSpan(5).seconds(), List.of(INFO_1, INFO_2), Option.empty(),
                                            BackoffConfig.DEFAULT, 5, 2);

            assertThat(config.coreMax()).isEqualTo(5);
            assertThat(config.coreMin()).isEqualTo(2);
        }
    }

    @Nested
    class SeedNodeDetection {

        @Test
        void isSeedNode_returnsTrueWhenSelfInCoreNodes() {
            var config = new TopologyConfig(NODE_1, 2, timeSpan(1).seconds(), timeSpan(1).seconds(), List.of(INFO_1, INFO_2));

            assertThat(config.isSeedNode()).isTrue();
        }

        @Test
        void isSeedNode_returnsFalseWhenSelfNotInCoreNodes() {
            var joiner = nodeId("joiner").unwrap();
            var config = new TopologyConfig(joiner, 2, timeSpan(1).seconds(), timeSpan(1).seconds(), List.of(INFO_1, INFO_2));

            assertThat(config.isSeedNode()).isFalse();
        }
    }
}
