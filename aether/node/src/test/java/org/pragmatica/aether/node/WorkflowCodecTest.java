// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowClaimKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowRunKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowClaimValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SystemTags;

import static org.assertj.core.api.Assertions.assertThat;

/// rc6 §4.2/§4.3: the workflow run and its claims travel through consensus, so their key, value and state types need pinned wire
/// tags (an unpinned one makes every node fail to boot) and must survive the node codec intact.
class WorkflowCodecTest {
    @Test
    void workflowTypes_havePinnedSystemTags() {
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowRunKey")).isEqualTo(2220);
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue")).isEqualTo(2221);
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState")).isEqualTo(2222);
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowClaimKey")).isEqualTo(2223);
        assertThat(SystemTags.tagFor("org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowClaimValue")).isEqualTo(2224);
    }

    @Test
    void runPut_roundTrips_throughTheNodeCodec_inEveryState() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var key = new WorkflowRunKey("wf-1");

        for (var state : WorkflowState.values()) {
            var value = new WorkflowRunValue("wf-1",
                                             "pilot",
                                             Map.of("node", "core-7", "target", "2.0.0"),
                                             3,
                                             2,
                                             5,
                                             1_760_000_000_000L,
                                             1_760_000_600_000L,
                                             1_760_000_100_000L,
                                             state,
                                             "postcondition 'node provisioned'",
                                             List.of("node:core-7", "community:east"),
                                             9L);
            var buf = Unpooled.buffer();

            codec.write(buf, new KVCommand.Put<AetherKey, AetherValue>(key, value));
            KVCommand.Put<?, ?> decoded = codec.read(buf);

            assertThat(decoded.key()).isEqualTo(key);
            assertThat(decoded.value()).as(state.name()).isEqualTo(value);
        }
        assertThat(key.asString()).isEqualTo("workflow-run/wf-1");
    }

    @Test
    void claimPut_roundTrips_throughTheNodeCodec() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var key = new WorkflowClaimKey("node:core-7");
        var value = new WorkflowClaimValue("wf-1");
        var buf = Unpooled.buffer();

        codec.write(buf, new KVCommand.Put<AetherKey, AetherValue>(key, value));
        KVCommand.Put<?, ?> decoded = codec.read(buf);

        assertThat(decoded.key()).isEqualTo(key);
        assertThat(decoded.value()).isEqualTo(value);
        assertThat(key.asString()).isEqualTo("workflow-claim/node:core-7");
    }
}
