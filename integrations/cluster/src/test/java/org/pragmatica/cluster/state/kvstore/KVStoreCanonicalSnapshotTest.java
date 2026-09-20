// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import org.junit.jupiter.api.Test;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.SliceCodec;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

class KVStoreCanonicalSnapshotTest {
    record Key(String name) implements StructuredKey {
        @Override public int hashCode() { return 7; }
    }

    @Test
    void snapshotsIgnoreInsertionOrderEvenWhenKeysCollide() {
        var codecs = new ArrayList<SliceCodec.TypeCodec<?>>(KvstoreCodecs.CODECS);
        codecs.add(new SliceCodec.TypeCodec<>(Key.class, SliceCodec.deterministicTag(Key.class.getName()),
            (codec, buffer, key) -> codec.write(buffer, key.name()),
            (codec, buffer) -> new Key((String) codec.read(buffer))));
        var codec = SliceCodec.sliceCodec(frameworkCodecs(), codecs);
        var first = new KVStore<Key, String>(MessageRouter.mutable(), codec, codec);
        var second = new KVStore<Key, String>(MessageRouter.mutable(), codec, codec);
        var a = new KVCommand.Put<>(new Key("a"), "one");
        var b = new KVCommand.Put<>(new Key("b"), "two");
        first.process(first.createBatch(List.of(a, b)));
        second.process(second.createBatch(List.of(b, a)));
        assertThat(first.makeSnapshot().unwrap()).isEqualTo(second.makeSnapshot().unwrap());
    }
}
