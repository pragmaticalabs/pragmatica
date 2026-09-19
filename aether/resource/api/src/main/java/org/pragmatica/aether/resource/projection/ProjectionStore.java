// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The read-model half of a [Projection] (durable-pubsub-spec §10): where folded state lives, plus
/// the projection-lifecycle state the facade needs from its backing — a persisted GENERATION
/// counter with its REBUILDING/LIVE state, and an atomic reset to a new generation.
///
/// The seam is deliberately backing-agnostic: a KV resource, a distributed cache, or an
/// entity-range backing all implement the same four operations, so the facade never churns when a
/// concrete `.into(...)` sugar arrives for one of them.
///
/// **The reset contract (settles spec §13 item 6, made atomic by #1304):** [#resetToNewGeneration]
/// advances the generation, clears EVERY read-model entry the projection wrote (KV-prefix clear, cache
/// clear, or entity range-delete) and enters REBUILDING, as ONE indivisible step. It must be one step:
/// were the bump and the clear separate, a write admitted under the new generation between them would
/// be wiped by the clear while its §8 claim stayed DONE, so the replay would be suppressed and the event
/// would vanish from the rebuilt model (#1304). The generation slot itself survives the clear — a reset
/// that wiped it would resurrect the prior pass's keys and dedup the whole replay into a no-op (spec
/// review finding 3).
///
/// **The generation fence (#1298):** [#write] carries the generation its fold was keyed under and
/// writes only while that is still the current generation, deciding it in the same indivisible step
/// as [#resetToNewGeneration]. A fold in flight across a rebuild is therefore refused
/// ([WriteOutcome#STALE_GENERATION]) however late it arrives.
///
/// **REBUILDING — the replay is the only writer, in offset order (#1304).** A new generation starts
/// REBUILDING over the [ReplayRange] the rebuild captured. While REBUILDING, a write at the current
/// generation is admitted only if its [DeliveryPosition] is its partition's NEXT replay offset
/// (starting at the range's `fromOffset`); admitting it advances that partition. A position BELOW the
/// next offset answers [WriteOutcome#ALREADY_APPLIED] without writing — offsets are admitted strictly in
/// order exactly once, so its effect is already in the model (a redelivery after a crash between write
/// and claim finalize). A later offset, or a write with no position while any partition is still
/// replaying, answers [WriteOutcome#REBUILDING] and writes nothing. A replay delivery that completes
/// WITHOUT a write — its claim was already DONE (#1304 X1) — reports [#markReplayed], which advances the
/// position exactly as an admitted write would. LIVE is PER PARTITION (#1304 X3): a partition goes live
/// once it passes its `throughOffset`, and a partition outside the range is live from the start; a live
/// partition admits writes on the generation fence alone. Ordering decides admission, never timing.
///
/// **Skipping what never reaches the fold (#1304, ruling (a)).** A replay offset the runtime
/// dead-lettered — a poison fold, an undecodable event quarantined raw — never arrives as a write, so
/// exact admission alone would hold its partition forever. The group's COMMITTED CURSOR is the positive
/// skip signal: the consumer commits past an event only once it is acknowledged or dead-lettered, so
/// [#cursorCommitted] moves the partition's next offset to `max(next, cursor)` and takes it LIVE once the
/// cursor passes its head. It can never skip an offset the replay has not finished, which is what makes
/// it safe where "accept any higher offset" is not: an early or zombie delivery would skip — and so lose —
/// every replay offset below it.
///
/// **Every report is STAMPED, because arrival order is not enough (#1304 X6).** Cursor commits are batched
/// and asynchronous (§6), and a zombie consumer can still be reporting its pre-rewind position, so a report
/// computed before the rewind can ARRIVE after it. Honoured, an in-range stale cursor makes the replay's own
/// offsets answer [WriteOutcome#ALREADY_APPLIED] — acknowledged, never written, silently lost. So
/// [#replayRewound] mints a [RewindToken] which the rebuild hands to whatever rewinds the cursor, every
/// report carries it, and a report stamped by any other rewind is ignored.
///
/// **Generation slot durability:** the counter must survive both [#reset] and process restart with
/// the same durability as the read model itself — it versions that model, and a model that
/// outlives its version marker dedups or replays wrongly after recovery.
public interface ProjectionStore<S> {
    /// What a generation-fenced [#write] did.
    enum WriteOutcome {
        /// The generation was current and the write admitted; the state is written.
        WRITTEN,
        /// REBUILDING, and this position was already applied in replay order; nothing was written and
        /// the event's effect is already in the model.
        ALREADY_APPLIED,
        /// The generation has moved on (a rebuild reset it); nothing was written.
        STALE_GENERATION,
        /// REBUILDING, and this write is not the next replay offset of its partition; nothing was written.
        REBUILDING
    }

    /// Where a delivery sits in the source topic: the ORDER key for REBUILDING admission, never an
    /// identity (see `MessageContext`).
    record DeliveryPosition(int partition, long offset) {}

    /// One partition's replay: `fromOffset` through `throughOffset` inclusive. Empty when `throughOffset`
    /// is below `fromOffset`.
    record PartitionRange(long fromOffset, long throughOffset) {}

    /// What a rebuild replays, per source partition, as captured before the reset. A partition absent
    /// from the map is not replayed, and a write positioned in it is refused while REBUILDING.
    record ReplayRange(Map<Integer, PartitionRange> partitions) {}

    Promise<Option<S>> read(String key);
    /// Write `state` under the generation fence and, while REBUILDING, the replay-order admission — see
    /// above. `position` is absent for a delivery that carries none, which REBUILDING refuses.
    Promise<WriteOutcome> write(String key, S state, long generation, Option<DeliveryPosition> position);
    /// In ONE indivisible step: advance the generation, clear the read model (preserving the generation
    /// slot), and enter REBUILDING over `range`. Returns the new generation.
    Promise<Long> resetToNewGeneration(ReplayRange range);
    /// A delivery at `position` completed without writing (its claim was already DONE). While `generation`
    /// is current and its partition is REBUILDING at exactly that offset, advance the partition past it —
    /// and take it LIVE if that was its head — as an admitted write would; otherwise change nothing.
    Promise<Unit> markReplayed(long generation, DeliveryPosition position);

    /// Identifies ONE rebuild's rewind. Only cursor reports stamped with the current rewind's token are
    /// honoured, so a report produced before it — however late it arrives — cannot move the replay.
    record RewindToken(long generation, long rewind) {}

    /// Mint the token for `generation`'s rewind, which the rebuild hands to whatever rewinds the cursor.
    /// Minting VOIDS any earlier token. A stale generation changes nothing and its token is already dead.
    Promise<RewindToken> replayRewound(long generation);
    /// The group's committed cursor for `partition` — the next offset it will read — as reported by the
    /// consumer the rewind started, stamped with that rewind's `token`. While that partition is REBUILDING,
    /// advance its next replay offset to `max(next, committedCursor)` and take it LIVE once the cursor
    /// passes its head. A report stamped by any other rewind, or arriving before one, changes nothing.
    Promise<Unit> cursorCommitted(RewindToken token, int partition, long committedCursor);
    /// Current generation; 0 when never reset.
    Promise<Long> generation();
}
