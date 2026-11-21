// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DrainCommandKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.RuntimeKey;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.aether.slice.kvstore.ReplicaFilter;
import org.pragmatica.aether.worker.metadata.WorkerMetadataIndex;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SystemTags;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/// The drain row's key (#3413 D1, C5a): `DrainCommandKey` crosses consensus, so it is pinned at its tag and round-trips; it is runtime
/// state (never in a backup), kept off the worker replica, and given no scope by the worker projection.
class DrainRowWireTest {
    private static final String KEY = "org.pragmatica.aether.slice.kvstore.AetherKey.DrainCommandKey";
    private static final NodeId TARGET = NodeId.nodeId("core-3").unwrap();

    @Test
    void theKey_hasItsAssignedSystemTag_andIsRegisteredInTheNodeCodec() {
        assertThat(SystemTags.tagFor(KEY)).isEqualTo(2962);

        var tagsByName = new HashMap<String, Integer>();

        NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs())
                  .registeredTypes()
                  .forEach((type, codec) -> tagsByName.put(type.getCanonicalName(), codec.tag()));
        assertThat(tagsByName).containsEntry(KEY, 2962);
    }

    @Test
    void theKey_roundTripsThroughTheNodeCodec() {
        var key = DrainCommandKey.drainCommandKey(TARGET);
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var first = Unpooled.buffer();

        codec.write(first, new KVCommand.Remove<AetherKey>(key));
        var encoded = ByteBufUtil.getBytes(first);
        KVCommand.Remove<?> decoded = codec.read(first);
        var second = Unpooled.buffer();

        codec.write(second, decoded);

        assertThat(decoded.key()).isEqualTo(key);
        assertThat(ByteBufUtil.getBytes(second)).isEqualTo(encoded);
        assertThat(first.readableBytes()).as("bytes left after decoding").isZero();
    }

    @Test
    void theKey_parsesFromItsOwnString_andRefusesAnyOther() {
        var key = DrainCommandKey.drainCommandKey(TARGET);

        assertThat(key.asString()).as("the string is in every mirror: it is a wire fact").isEqualTo("drain-command/core-3");
        assertThat(DrainCommandKey.drainCommandKey(key.asString()).unwrap()).isEqualTo(key);
        assertThat(DrainCommandKey.drainCommandKey("drain-deadline/core-3").isFailure()).isTrue();
        assertThat(DrainCommandKey.drainCommandKey("drain-command/").isFailure()).isTrue();
    }

    @Test
    void theKey_parsesBackToItsOwnNodeId_whateverTheTargetLooksLike() {
        // multi-digit id, a non-core prefix, and ids made of the separator's neighbours: a '/', the prefix itself, a trailing '/'
        for (var id : new String[]{"core-12345", "worker-7", "edge-a-b", "x/y/z", "drain-command/core-3", "core-3/", "/core-3"}) {
            var target = NodeId.nodeId(id).unwrap();
            var parsed = DrainCommandKey.drainCommandKey("drain-command/" + id).unwrap();

            assertThat(parsed).as("target " + id).isEqualTo(DrainCommandKey.drainCommandKey(target));
            assertThat(parsed.target().id()).as("target " + id).isEqualTo(id);
            assertThat(DrainCommandKey.drainCommandKey(parsed.asString()).unwrap()).as("round trip " + id).isEqualTo(parsed);
        }
    }

    @Test
    void theKey_formatError_namesTheOffendingTextAndTheExpectedShape() {
        assertThat(DrainCommandKey.drainCommandKey("drain-deadline/core-3").fold(Cause::message, _ -> "parsed"))
            .isEqualTo("Invalid drain-command key format: drain-deadline/core-3");
        assertThat(DrainCommandKey.drainCommandKey("drain-command/").fold(Cause::message, _ -> "parsed"))
            .isEqualTo("Invalid drain-command key format: drain-command/");
    }

    @Test
    void theKey_rendersItsStoredForm_inToString() {
        var key = DrainCommandKey.drainCommandKey(TARGET);

        assertThat(key.toString()).isEqualTo("drain-command/core-3");
        assertThat(DrainCommandKey.drainCommandKey(key.toString()).unwrap()).isEqualTo(key);
    }

    @Test
    void theKey_isRuntimeState_notBackedUp() {
        var key = DrainCommandKey.drainCommandKey(TARGET);

        assertThat(key).isInstanceOf(RuntimeKey.class);
        assertThat(BackupEntryCodec.isBackedUp(key)).as("a backup never carries a drain command").isFalse();
    }

    @Test
    void theReplicaFilter_excludesTheKey() {
        assertThat(ReplicaFilter.replicaFilter().excludes(DrainCommandKey.drainCommandKey(TARGET))).isTrue();
    }

    @Test
    void theWorkerProjection_givesTheKeyNoScope_soTheRevisionDoesNotMove() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var before = index.revision();

        index.put(DrainCommandKey.drainCommandKey(TARGET), "opaque: the unscoped arm never reads the value");

        assertThat(index.revision()).as("no scope, so no revision bump").isEqualTo(before);
        assertThat(index.snapshot(WorkerMetadataIndex.GLOBAL)).doesNotContainKey(DrainCommandKey.drainCommandKey(TARGET));
        assertThat(index.hasScope("node:core-3")).isFalse();
    }
}
