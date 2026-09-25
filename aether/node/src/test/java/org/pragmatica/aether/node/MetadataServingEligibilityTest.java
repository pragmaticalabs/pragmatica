// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataServingEligibilityTest {
    @Test
    @SuppressWarnings("unchecked")
    void stagedObserverAndPausedCoreCannotServeButActiveCoreCan() {
        var node = (RabiaNode<KVCommand<AetherKey>>) mock(RabiaNode.class);
        when(node.isObserving()).thenReturn(true);
        assertThat(AetherNode.metadataServingReady(false, node)).as("staged observing core").isFalse();
        when(node.isObserving()).thenReturn(false);
        assertThat(AetherNode.metadataServingReady(false, node)).as("paused core").isFalse();
        when(node.isActive()).thenReturn(true);
        assertThat(AetherNode.metadataServingReady(false, node)).as("active core").isTrue();
        assertThat(AetherNode.metadataServingReady(true, node)).as("immutable worker role").isFalse();
    }
}
