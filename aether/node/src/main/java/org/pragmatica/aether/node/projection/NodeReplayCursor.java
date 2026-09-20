// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.pragmatica.aether.node.projection.PartitionBounds.Bounds;
import org.pragmatica.aether.node.stream.ConsumerAssignmentWriter.CommittedAssignments;
import org.pragmatica.aether.resource.projection.Projection.ReplayCursor;
import org.pragmatica.aether.resource.projection.ProjectionStore.PartitionRange;
import org.pragmatica.aether.resource.projection.ProjectionStore.ReplayRange;
import org.pragmatica.aether.resource.projection.ProjectionStore.RewindToken;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The node's [ReplayCursor] for one attached projection (#1333): the seam `Projection.rebuild` drives.
///
/// **capture** — one [PartitionRange] per partition of the topic stream, `earliestRetained` through
/// `visibleHead`, from [PartitionBounds]; a partition with nothing visible is left out (LIVE from the
/// start). Moves nothing; a refused bound refuses the whole capture before the store is touched.
///
/// **mintRewindToken** — from COMMITTED state: strictly newer than every checkpoint epoch the group holds.
///
/// **rewind** — for every captured partition, PUT the group's `StreamCursorCheckpointKey` to the REWIND
/// RECORD `(fromOffset, token = the committed assignee's, epoch = token, rewind = true)`. The value is
/// `EpochBearing` and MINTS its epoch, so the applier refuses it unless strictly newer than the committed
/// record, and refuses every later checkpoint stamped with an older epoch — the zombie fence. The key is
/// also `AssignmentGuarded` (#1271): the record carries the token of the partition's COMMITTED assignment,
/// read here at put time — no committed assignment refuses the rewind before anything is put, and an
/// assignment that moves between the read and the apply is refused by the applier's guard. The group id is
/// resolved NOW (an inferred subscriber that turned out ambiguous refuses the rewind rather than rewinding
/// a guess). Then the committed value is READ BACK and the rewind fails unless it IS the record put: a
/// refused mint (another rebuild of the group committed first, or a moved assignment) would otherwise
/// leave the store REBUILDING with nothing saying why. The consumer restart is level-triggered from the
/// committed epoch (`StreamConsumerManager`), so the rewind is complete when the records are committed;
/// the rewound consumer resumes at `fromOffset` under the token.
public record NodeReplayCursor(String topicStream,
                               Supplier<Result<String>> groupId,
                               Supplier<Option<Integer>> partitionCount,
                               PartitionBounds bounds,
                               Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> commandWriter,
                               Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> committedReader,
                               CommittedAssignments committedAssignments) implements ReplayCursor {
    private static final Logger log = LoggerFactory.getLogger(NodeReplayCursor.class);

    sealed interface RewindError extends Cause {
        record StreamUnknown(String topicStream) implements RewindError {
            @Override
            public String message() {
                return "Topic stream " + topicStream
                     + " is not known to this node yet, so its partitions cannot be captured";
            }
        }

        /// #1271: the partition has no committed consumer assignment, so no write to its cursor can pass
        /// the applier's guard — the rewind is refused before anything is put.
        record NoCommittedAssignment(String topicStream, String groupId, int partition) implements RewindError {
            @Override
            public String message() {
                return "Rewind of group " + groupId
                     + " on " + topicStream
                     + "[" + partition
                     + "] refused: the partition has no committed consumer assignment, and the cluster cursor"
                     + " admits writes only from the committed assignee";
            }
        }

        record RewindNotCommitted(String topicStream,
                                  String groupId,
                                  int partition,
                                  RewindToken token,
                                  Option<RewindEpoch> committed) implements RewindError {
            @Override
            public String message() {
                return "Rewind of group " + groupId
                     + " on " + topicStream
                     + "[" + partition
                     + "] to token " + token
                     + " was not committed: the cluster cursor carries epoch " + committed.map(RewindEpoch::toString)
                                                                                          .or("<absent>")
                     + ". The applier refuses a rewind record unless its epoch is strictly newer than the committed"
                     + " one and its assignment token is the committed assignee's: another rebuild of this group"
                     + " committed first (retry to mint past it), the assignment moved, or the put did not apply";
            }
        }
    }

    @Override
    public Promise<ReplayRange> capture() {
        return partitionCount.get()
                             .map(this::captureAll)
                             .or(() -> new RewindError.StreamUnknown(topicStream).promise());
    }

    private Promise<ReplayRange> captureAll(int count) {
        var captures = IntStream.range(0, count)
                                .mapToObj(partition -> bounds.bounds(topicStream, partition)
                                                             .map(range -> Map.entry(partition, range)))
                                .toList();

        return Promise.allOf(captures)
                      .flatMap(results -> Result.allOf(results).async())
                      .map(NodeReplayCursor::toRange);
    }

    private static ReplayRange toRange(List<Map.Entry<Integer, Option<Bounds>>> captured) {
        var partitions = new HashMap<Integer, PartitionRange>();

        captured.forEach(entry -> entry.getValue()
                                       .onPresent(range -> partitions.put(entry.getKey(),
                                                                          new PartitionRange(range.earliestRetained(),
                                                                                             range.visibleHead()))));

        return new ReplayRange(Map.copyOf(partitions));
    }

    /// The token is minted from COMMITTED state (#1333 review): the group's checkpoint on every partition is
    /// read, and the token is strictly newer than the newest epoch found — `(max(generation, committed
    /// generation), committed rewind + 1)`. A process-local counter (the store's generation restarts at 0 on
    /// a fresh store) would re-mint an epoch the cluster already holds; the applier accepts an equal-epoch
    /// checkpoint, so the running consumer's next checkpoint would drive the EMPTY rebuilt model LIVE.
    /// Partitions are those the stream declares, not only the captured ones — an empty partition's
    /// committed epoch still bounds the mint.
    @Override
    public Promise<RewindToken> mintRewindToken(long generation) {
        return groupId.get()
                      .async()
                      .flatMap(group -> partitionCount.get()
                                                      .map(count -> Promise.success(mint(group, count, generation)))
                                                      .or(() -> new RewindError.StreamUnknown(topicStream).promise()));
    }

    private RewindToken mint(String group, int count, long generation) {
        var newest = IntStream.range(0, count)
                              .mapToObj(partition -> committed(group, partition).map(StreamCursorCheckpointValue::rewindEpoch))
                              .flatMap(Option::stream)
                              .max(RewindEpoch::compareTo)
                              .orElse(RewindEpoch.NONE);

        return new RewindToken(Math.max(generation, newest.generation()),
                               newest.rewind() + 1);
    }

    @Override
    public Promise<Unit> rewind(ReplayRange range, RewindToken token) {
        return groupId.get()
                      .async()
                      .flatMap(group -> rewindAll(group, range, token));
    }

    /// One REWIND RECORD per partition — `rewind = true`, so the applier refuses it unless its epoch is
    /// STRICTLY newer than the committed one — under the partition's committed assignment token. The
    /// record instances are kept so the read-back can compare the whole committed value, not just the
    /// epoch: a refused mint leaves the previous record in place. All records go in ONE apply.
    private Promise<Unit> rewindAll(String group, ReplayRange range, RewindToken token) {
        var epoch = epochOf(token);

        return Result.allOf(range.partitions()
                                 .entrySet()
                                 .stream()
                                 .map(entry -> rewindRecord(group, entry.getKey(), entry.getValue().fromOffset(), epoch))
                                 .toList())
                     .async()
                     .flatMap(records -> put(group, toRecords(records), range, token, epoch));
    }

    private Result<Map.Entry<Integer, StreamCursorCheckpointValue>> rewindRecord(String group,
                                                                                int partition,
                                                                                long fromOffset,
                                                                                RewindEpoch epoch) {
        return assignmentToken(group, partition).map(assignment -> Map.entry(partition,
                                                                             StreamCursorCheckpointValue.rewindRecord(fromOffset,
                                                                                                                      assignment,
                                                                                                                      epoch)));
    }

    private Result<AssignmentToken> assignmentToken(String group, int partition) {
        return committedAssignments.assignmentOf(topicStream, partition, group)
                                   .map(ConsumerAssignmentValue::token)
                                   .toResult(new RewindError.NoCommittedAssignment(topicStream, group, partition));
    }

    private static Map<Integer, StreamCursorCheckpointValue> toRecords(List<Map.Entry<Integer, StreamCursorCheckpointValue>> entries) {
        var records = new HashMap<Integer, StreamCursorCheckpointValue>();

        entries.forEach(entry -> records.put(entry.getKey(), entry.getValue()));

        return records;
    }

    private Promise<Unit> put(String group,
                              Map<Integer, StreamCursorCheckpointValue> records,
                              ReplayRange range,
                              RewindToken token,
                              RewindEpoch epoch) {
        var puts = records.entrySet()
                          .stream()
                          .map(entry -> (KVCommand<AetherKey>) new KVCommand.Put<AetherKey, AetherValue>(checkpointKey(group,
                                                                                                                       entry.getKey()),
                                                                                                         entry.getValue()))
                          .toList();

        return commandWriter.apply(puts)
                            .flatMap(_ -> verifyCommitted(group, records, token))
                            .onSuccess(_ -> log.info("Rewound group {} on {} to {} under epoch {}",
                                                     group,
                                                     topicStream,
                                                     range.partitions(),
                                                     epoch));
    }

    /// The put resolved, which says the command was APPLIED, not that it was ACCEPTED — a fenced refusal is
    /// silent. Reading back what the applier committed is the only way to know, and it is the RECORD that is
    /// compared: an equal-epoch mint refused by the applier leaves the earlier record, whose epoch may equal
    /// the token's.
    private Promise<Unit> verifyCommitted(String group,
                                          Map<Integer, StreamCursorCheckpointValue> records,
                                          RewindToken token) {
        var refused = records.entrySet()
                             .stream()
                             .map(entry -> checkCommitted(group,
                                                          entry.getKey(),
                                                          entry.getValue(),
                                                          token))
                             .toList();

        return Result.allOf(refused)
                     .mapToUnit()
                     .async();
    }

    private Result<Unit> checkCommitted(String group,
                                        int partition,
                                        StreamCursorCheckpointValue record,
                                        RewindToken token) {
        var committed = committed(group, partition);

        return committed.filter(record::equals)
                        .isPresent()
               ? Result.unitResult()
               : new RewindError.RewindNotCommitted(topicStream,
                                                    group,
                                                    partition,
                                                    token,
                                                    committed.map(StreamCursorCheckpointValue::rewindEpoch)).result();
    }

    private Option<StreamCursorCheckpointValue> committed(String group, int partition) {
        return committedReader.apply(checkpointKey(group, partition));
    }

    private StreamCursorCheckpointKey checkpointKey(String group, int partition) {
        return StreamCursorCheckpointKey.streamCursorCheckpointKey(topicStream, partition, group);
    }

    public static RewindEpoch epochOf(RewindToken token) {
        return RewindEpoch.rewindEpoch(token.generation(), token.rewind());
    }

    public static RewindToken tokenOf(RewindEpoch epoch) {
        return new RewindToken(epoch.generation(), epoch.rewind());
    }
}
