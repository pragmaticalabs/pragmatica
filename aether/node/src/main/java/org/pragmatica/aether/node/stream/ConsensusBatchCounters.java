// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The consensus load of consumer progress, by key class (#2590, the P0.4 instrument moved to P1): how many batches the
/// cursor command writer proposed, and how many commands of each class they carried. The S7 arms read it before and
/// after progress moves off consensus; it is exposed as node gauges, no endpoint of its own.
///
/// The class is decided by the KEY: a cursor key's `Put` is a checkpoint, unless its value is the rewind record (today a
/// rewind is a flagged cursor value, `StreamCursorCheckpointValue.rewind`), a cursor key's `Noop` is a forwarding node's
/// read barrier, an entity checkpoint key is an entity pointer, anything else is other.
public final class ConsensusBatchCounters {
    public enum KeyClass {
        CURSOR_PUT,
        CURSOR_NOOP,
        REWIND,
        ENTITY_POINTER,
        OTHER
    }

    private final AtomicLong batches = new AtomicLong();
    private final Map<KeyClass, AtomicLong> commands = new EnumMap<>(KeyClass.class);

    private ConsensusBatchCounters() {
        for (var keyClass : KeyClass.values()) {
            commands.put(keyClass, new AtomicLong());
        }
    }

    public static ConsensusBatchCounters consensusBatchCounters() {
        return new ConsensusBatchCounters();
    }

    /// `writer`, counting every batch it is handed and every command in it before passing the batch on unchanged.
    public Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> counting(Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> writer) {
        return batch -> writer.apply(counted(batch));
    }

    private List<KVCommand<AetherKey>> counted(List<KVCommand<AetherKey>> batch) {
        batches.incrementAndGet();
        batch.forEach(command -> commands.get(keyClass(command))
                                         .incrementAndGet());

        return batch;
    }

    /// Batches proposed through the counted writer since boot.
    public long batches() {
        return batches.get();
    }

    /// Commands of `keyClass` proposed through the counted writer since boot.
    public long commands(KeyClass keyClass) {
        return commands.get(keyClass)
                       .get();
    }

    static KeyClass keyClass(KVCommand<AetherKey> command) {
        return switch (command.key()) {
            case StreamCursorCheckpointKey _ -> cursorClass(command);
            case EntityCheckpointKey _ -> KeyClass.ENTITY_POINTER;
            default -> KeyClass.OTHER;
        };
    }

    private static KeyClass cursorClass(KVCommand<AetherKey> command) {
        return switch (command) {
            case KVCommand.Put<AetherKey, ?> put when put.value() instanceof StreamCursorCheckpointValue value && value.rewind() -> KeyClass.REWIND;
            case KVCommand.Put<AetherKey, ?>_ -> KeyClass.CURSOR_PUT;
            case KVCommand.Noop<AetherKey>_ -> KeyClass.CURSOR_NOOP;
            default -> KeyClass.OTHER;
        };
    }
}
