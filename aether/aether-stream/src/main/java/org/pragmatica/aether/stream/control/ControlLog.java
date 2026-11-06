// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.stream.PartitionLogEvents;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// A control log (#2590 P1, §5.2): records with a latest-per-key index, kept on the stream substrate instead of in
/// consensus. Each partition's owner is the single decider: it runs the record kind's [Admission] against its admitted
/// view on the partition's serial queue, appends the admitted records as one run, and acknowledges them only after the
/// WAL fsync and the confirmation barrier. Every node keeps the committed index of the partitions it holds, fed only from
/// committed offsets, rebuilt from the log whenever it might be wrong.
///
/// P1 has no user: nothing in production builds one. P2 moves consumer cursors onto it (`system:cursors`), P3 entity fold
/// markers.
public interface ControlLog extends CarryForward {
    /// Appends `records` to `(streamName, partition)`, one outcome per record, in order. On the owner it runs locally;
    /// from any other node it is forwarded to the owner as one batch. A CARRY is refused here: only the owner writes one.
    Promise<List<ControlOutcome>> append(String streamName, int partition, List<ControlRecord> records);

    /// The owner-side entry: admission and append here, never forwarded. The forwarded batch-append lands here.
    Promise<List<ControlOutcome>> appendAsOwner(String streamName, int partition, List<ControlRecord> records);

    /// The key's latest COMMITTED record on this node, [Option#none] when the valid index holds none. Fails with
    /// [ControlLogError.General#INDEX_UNAVAILABLE] while the index is not valid: an invalid index is never read as empty.
    Result<Option<IndexedRecord>> latest(String streamName, int partition, String key);
    /// The retention floor of `(streamName, partition)`: see [ControlRetentionFloor].
    long deletableThroughOffset(String streamName, int partition);
    /// What the partition manager tells this log: bind with `StreamPartitionManager#partitionLogEvents`.
    PartitionLogEvents events();

    /// Where an append for a partition owned elsewhere goes: the forwarded batch-append.
    @FunctionalInterface
    interface RemoteAppend {
        Promise<List<ControlOutcome>> append(NodeId owner,
                                             String streamName,
                                             int partition,
                                             List<ControlRecord> records);
    }

    /// The committed owner of a partition as this node sees it, [Option#none] when unknown.
    @FunctionalInterface
    interface OwnerResolver {
        Option<NodeId> ownerOf(String streamName, int partition);
    }

    /// A control log that never forwards: every append runs here as the owner.
    static ControlLog controlLog(ControlPartitionLog partitionLog,
                                 ControlKind.Admissions admissions,
                                 MirrorView mirror) {
        return new DefaultControlLog(partitionLog,
                                     admissions,
                                     mirror,
                                     Option.none(),
                                     (_, _) -> Option.none(),
                                     Option.none());
    }

    /// A control log that forwards an append to a remote owner through `remote`.
    static ControlLog controlLog(ControlPartitionLog partitionLog,
                                 ControlKind.Admissions admissions,
                                 MirrorView mirror,
                                 NodeId self,
                                 OwnerResolver owners,
                                 RemoteAppend remote) {
        return new DefaultControlLog(partitionLog, admissions, mirror, Option.some(self), owners, Option.some(remote));
    }
}

final class DefaultControlLog implements ControlLog {
    private final ControlPartitionLog partitionLog;
    private final ControlKind.Admissions admissions;
    private final MirrorView mirror;
    private final Option<NodeId> self;
    private final OwnerResolver owners;
    private final Option<RemoteAppend> remote;
    private final Map<PartitionId, PartitionControlState> states = new ConcurrentHashMap<>();

    private record PartitionId(String streamName, int partition) {}

    DefaultControlLog(ControlPartitionLog partitionLog,
                      ControlKind.Admissions admissions,
                      MirrorView mirror,
                      Option<NodeId> self,
                      OwnerResolver owners,
                      Option<RemoteAppend> remote) {
        this.partitionLog = partitionLog;
        this.admissions = admissions;
        this.mirror = mirror;
        this.self = self;
        this.owners = owners;
        this.remote = remote;
    }

    @Override
    public Promise<List<ControlOutcome>> append(String streamName, int partition, List<ControlRecord> records) {
        return owners.ownerOf(streamName, partition)
                     .filter(this::isRemote)
                     .flatMap(owner -> remote.map(forward -> forward.append(owner, streamName, partition, records)))
                     .or(() -> appendAsOwner(streamName, partition, records));
    }

    @Override
    public Promise<List<ControlOutcome>> appendAsOwner(String streamName, int partition, List<ControlRecord> records) {
        return records.isEmpty()
               ? Promise.success(List.of())
               : state(streamName, partition).append(records);
    }

    @Override
    public Promise<List<ControlOutcome>> carryForward(String streamName, int partition, long belowOffset) {
        return state(streamName, partition).carryForward(belowOffset);
    }

    @Override
    public Result<Option<IndexedRecord>> latest(String streamName, int partition, String key) {
        return state(streamName, partition).latest(key);
    }

    @Override
    public long deletableThroughOffset(String streamName, int partition) {
        return Option.option(states.get(new PartitionId(streamName, partition)))
                     .map(PartitionControlState::deletableThroughOffset)
                     .or(-1L);
    }

    @Override
    public PartitionLogEvents events() {
        return new PartitionLogEvents() {
            @Contract
            @Override
            public void committed(String streamName, int partition, long through) {
                existing(streamName, partition).onPresent(state -> state.committed(through));
            }

            @Contract
            @Override
            public void truncated(String streamName, int partition, long keepThrough) {
                existing(streamName, partition).onPresent(state -> state.truncated(keepThrough));
            }
        };
    }

    /// Forwardable only when known to differ from this node, as in the stream write router.
    private boolean isRemote(NodeId owner) {
        return self.map(node -> !owner.equals(node))
                   .or(false);
    }

    private Option<PartitionControlState> existing(String streamName, int partition) {
        return Option.option(states.get(new PartitionId(streamName, partition)));
    }

    /// The state of a partition, built on first use: INVALID until its first rebuild.
    PartitionControlState state(String streamName, int partition) {
        return states.computeIfAbsent(new PartitionId(streamName, partition),
                                      id -> new PartitionControlState(id.streamName(),
                                                                      id.partition(),
                                                                      partitionLog,
                                                                      admissions,
                                                                      mirror));
    }
}
