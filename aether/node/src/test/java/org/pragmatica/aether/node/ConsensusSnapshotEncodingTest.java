// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConsensusSnapshotEncodingTest {
    @Test
    void persistedPhaseHeaderDoesNotPreventSnapshotRestoration() {
        var snapshot = new byte[]{0, 1, 2, -1, 42};
        var payload = Base64.getEncoder().encodeToString(snapshot);
        assertThat(AetherNode.base64ToSnapshot("# Phase: 123\n" + payload).unwrap()).containsExactly(snapshot);
        assertThat(AetherNode.base64ToSnapshot(payload).unwrap()).containsExactly(snapshot);
    }

    @Test
    void malformedHeaderAndPayloadRemainFailures() {
        assertThat(AetherNode.base64ToSnapshot("# Phase: unknown\nAA==").isFailure()).isTrue();
        assertThat(AetherNode.base64ToSnapshot("# Phase: 123\nnot base64!").isFailure()).isTrue();
    }
}
