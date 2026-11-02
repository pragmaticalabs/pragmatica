/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// v-2509 delta: the remove path's rollback follows the fence verdict only. A remove that fails with NO fence refusal (both remotes
/// silent) keeps the writer's own tombstone, exactly as before #2258; mutating the 4-arg `afterFailedWrite` to roll back on every
/// failure survived the 117-test delta scope, so nothing pinned this.
class RemoveWithoutFenceNoRollbackTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(2).seconds());
    private static final NodeId WRITER = new NodeId("writer");
    private static final byte[] KEY = "stream-segments/remove-shape".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PRIOR = "prior".getBytes(StandardCharsets.UTF_8);

    @Test
    void aRemoveThatFailsWithoutAFence_keepsTheWritersTombstone_andDoesNotRestoreTheValue() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        List.of(WRITER, new NodeId("a"), new NodeId("b")).forEach(ring::addNode);
        var node = dhtNode(WRITER, memoryStorageEngine(), ring, CONFIG);

        node.storage().putVersioned(KEY, PRIOR, 1L, 0L, 0L, 0L).await();
        var client = distributedDHTClient(node, (target, message) -> {}, CONFIG);
        var outcome = client.remove(KEY).await();

        assertThat(outcome.isFailure()).as("both remotes silent: the quorum is not reached").isTrue();
        assertThat(node.getLocal(KEY).await().or(Option.none()).isPresent())
            .as("no replica fenced the remove, so the local tombstone stays and the prior value is NOT restored")
            .isFalse();
    }
}
