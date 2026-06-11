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

package org.pragmatica.consensus;

import org.pragmatica.consensus.rabia.CorrelationId;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/// Replicated state machine interface.
/// Implementations must ensure deterministic command execution.
///
/// The state machine is batch-oriented: consensus operates on [Batch] values, and
/// [#process(Batch)] is the single command-application entry point. Batch identity and
/// creation are centralized here via [#createBatch(List)] and [#merge(Batch, Batch)] so a
/// single, deterministic content id is produced for every batch across the cluster.
///
/// @param <C> Command type
public interface StateMachine<C extends Command> {
    Logger log = LoggerFactory.getLogger(StateMachine.class);

    /// Process a batch of commands in order and update the machine's state.
    /// Each command must be immutable and its execution must be deterministic.
    ///
    /// @param batch The batch of commands to process
    /// @return The results of processing each command, in order
    <R> List<R> process(Batch<C> batch);

    /// Create a snapshot of the current state machine state.
    /// The snapshot should be serializable and capture the complete state.
    ///
    /// @return A Result containing the serialized state snapshot
    Result<byte[]> makeSnapshot();

    /// Restore the machine's state from a snapshot.
    /// This should completely replace the current state with the state from the snapshot.
    ///
    /// SILENT install (cluster-topology-overhaul §5.8, AMENDED 2026-06-11): restoring a snapshot
    /// installs the synced state WITHOUT firing any notifications. Notification delivery is
    /// deferred to [#replayNotifications()], which the engine calls AFTER it has become ACTIVE.
    /// This buys the invariant "a KV notification implies the engine is ACTIVE" structurally —
    /// no consumer ever checks activation status.
    ///
    /// @return A Result indicating success or failure
    Result<Unit> restoreSnapshot(byte[] snapshot);

    /// Replay the current state as a notification burst — the `sync → activate → replay` step
    /// (cluster-topology-overhaul §5.8, AMENDED 2026-06-11). Called by the engine as the FIRST
    /// work on the apply path once it is ACTIVE, with live applies queued strictly behind it, so
    /// consumers observe a totally-ordered "world-as-of-N, then N+1 onward" stream.
    ///
    /// First replay after a SILENT install fires one synthetic put per key. A mid-life install on
    /// an already-replayed machine does DIFF-replay (puts for new/changed keys, removes for
    /// vanished ones) against the previously-replayed view. Replay is NOTIFICATION SYNTHESIS ONLY
    /// — it MUST NOT re-apply commands or mutate storage (the H4 `LeaderKey` fence must never see
    /// replay as a competing write). Default no-op for state machines that have no notification
    /// surface.
    default Unit replayNotifications() {
        return Unit.unit();
    }

    /// Reset state machine to its initial state.
    Unit reset();

    /// The serializer this state machine uses for snapshot serialization. The capability is
    /// intrinsic to being a `StateMachine` (its `makeSnapshot`/`restoreSnapshot` API already
    /// implies it serializes); [#createBatch(List)] reuses it to derive a deterministic,
    /// content-based batch id via the same on-the-wire `@Codec` encoding.
    Serializer serializer();

    /// Single creation point for command batches. Identity is derived from the commands
    /// only (correlationIds / timestamp are excluded from the id), guaranteeing the same
    /// command list yields the same id on every node. The id is the SHA-256 of the
    /// `serializer()`-encoded command list, so identical commands hash identically across
    /// nodes (the encoding must be deterministic — no `Map`/`Set` iteration-order or
    /// identity in command fields).
    default Batch<C> createBatch(List<C> commands) {
        return Batch.create(serializer(), commands);
    }

    /// Merges two batches that share the same id. Combines their correlationIds (this
    /// batch's first, then the other's) and keeps the earliest timestamp; the id and
    /// commands of the first batch are retained.
    ///
    /// Two batches with the same id are required to have identical content (the id is a
    /// content hash). Differing ids indicate a programming error and are rejected.
    default Batch<C> merge(Batch<C> a, Batch<C> b) {
        if (!a.id().equals(b.id())) {
            log.error("Cannot merge batches with different ids: {} vs {}", a.id(), b.id());
            throw new IllegalArgumentException("Cannot merge batches with different ids: " + a.id() + " vs " + b.id());
        }
        var merged = new ArrayList<>(a.correlationIds());
        merged.addAll(b.correlationIds());
        var earliestTimestamp = Math.min(a.timestamp(), b.timestamp());
        return new Batch<>(a.id(), List.copyOf(merged), earliestTimestamp, a.commands());
    }

    /// Represents a proposal value (batch of commands) in the consensus protocol.
    /// The [Id] is content-based (hash of commands) to enable consolidation of identical
    /// batches across nodes.
    ///
    /// @param <C> Command type
    @Codec
    record Batch<C extends Command>(Id id,
                                    List<CorrelationId> correlationIds,
                                    long timestamp,
                                    List<C> commands) implements Comparable<Batch<C>> {
        public Batch {
            commands = List.copyOf(commands);
            correlationIds = List.copyOf(correlationIds);
        }

        @Override
        public int compareTo(Batch<C> o) {
            var timestampCompare = Long.compare(timestamp, o.timestamp);
            if (timestampCompare != 0) {
                return timestampCompare;
            }
            return id.id().compareTo(o.id.id());
        }

        public static <C extends Command> Batch<C> create(Serializer serializer, List<C> commands) {
            var batchId = new Id("batch-" + sha256Hex(serializer.encode(commands)));
            return new Batch<>(batchId,
                               List.of(CorrelationId.randomCorrelationId()),
                               System.nanoTime(),
                               commands);
        }

        /// SHA-256 of the given bytes, lowercase hex. A fresh `MessageDigest` per call (the
        /// instance is not thread-safe); cheap, runs on the caller thread.
        private static String sha256Hex(byte[] bytes) {
            return HexFormat.of().formatHex(sha256().digest(bytes));
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
        }

        public static <C extends Command> Batch<C> emptyBatch() {
            return new Batch<>(Id.emptyId(),
                               List.of(CorrelationId.emptyCorrelationId()),
                               System.nanoTime(),
                               List.of());
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (! (o instanceof Batch<?> batch)) return false;
            return id.equals(batch.id);
        }

        public boolean isNotEmpty() {
            return ! commands.isEmpty();
        }

        /// Unique identifier for a command batch.
        @Codec
        public record Id(String id) implements Comparable<Id> {
            public static Result<Id> id(String id) {
                return Verify.ensure(id, Verify.Is::notBlank)
                             .map(Id::new);
            }

            public static Id randomId() {
                return new Id(IdGenerator.generate("batch"));
            }

            public static Id emptyId() {
                return new Id("empty");
            }

            @Override
            public int compareTo(Id o) {
                return id.compareTo(o.id);
            }
        }
    }
}
