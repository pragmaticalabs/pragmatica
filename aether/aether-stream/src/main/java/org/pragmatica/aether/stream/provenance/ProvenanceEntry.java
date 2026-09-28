// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.provenance;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;
import org.pragmatica.storage.AppendLog.EpochKey;
import org.pragmatica.storage.AppendLog.EpochOrder;
import org.pragmatica.storage.AppendLog.EpochStart;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// One entry of a partition log's owner-epoch history (#1596, spec #1569 §7.5.1): [#epoch] began writing the log
/// at `startOffset`. A real epoch is the one committed in `StreamPartitionOwnershipValue` under which the record
/// was first published -- never the fence token and never a receiver's local view; see [ProvenanceEpoch] for the
/// synthetic kinds.
///
/// The components are the epoch flattened for the wire: `kind`, `rank` (the real epoch, or a synthetic one's
/// floor) and `id` (a real epoch's incarnation ULID, or a synthetic one's `d`). Build one only through the
/// factories, which keep the three consistent; read it through [#epoch].
/// Flattened, not a [ProvenanceEpoch]-typed component, because of #1633: a record field typed by a `@Codec` sealed
/// interface is generated as a call to a parent codec the processor never emits. Un-flatten once #1633 is fixed.
///
/// The log stores the epoch as an opaque [EpochKey] ([ProvenanceEpoch#token]) and orders consecutive entries by
/// [#ORDER].
@Codec
public record ProvenanceEntry(ProvenanceKind kind, Epoch rank, Option<String> id, long startOffset) {
    /// The order the log enforces between consecutive entries ([ProvenanceEpoch#follows]). A key this codec did
    /// not write follows nothing, so the log refuses it; two real keys of the same epoch that differ only in the
    /// ULID follow neither way, so one log never holds both (#1625).
    public static final EpochOrder ORDER = ProvenanceEntry::follows;

    /// The wire form of [ProvenanceEpoch]'s kinds; `UNATTRIBUTED` is the spec's `UNKNOWN(d)`.
    @Codec
    public enum ProvenanceKind {
        OWNED,
        UNATTRIBUTED,
        BASE,
        UNKNOWN
    }

    public static ProvenanceEntry provenanceEntry(ProvenanceEpoch epoch, long startOffset) {
        return switch (epoch) {
            case ProvenanceEpoch.Owned owned -> new ProvenanceEntry(ProvenanceKind.OWNED,
                                                                    owned.epoch(),
                                                                    owned.incarnationUlid(),
                                                                    startOffset);
            case ProvenanceEpoch.Unknown unknown -> new ProvenanceEntry(ProvenanceKind.UNATTRIBUTED,
                                                                        unknown.floor(),
                                                                        some(unknown.d()),
                                                                        startOffset);
            case ProvenanceEpoch.Base base -> new ProvenanceEntry(ProvenanceKind.BASE,
                                                                  base.floor(),
                                                                  some(base.d()),
                                                                  startOffset);
        };
    }

    /// A real owner epoch with no incarnation ULID yet (#1625, reserved).
    public static ProvenanceEntry provenanceEntry(Epoch epoch, long startOffset) {
        return new ProvenanceEntry(ProvenanceKind.OWNED, epoch, none(), startOffset);
    }

    public static ProvenanceEntry provenanceEntry(Epoch epoch, Option<String> incarnationUlid, long startOffset) {
        return new ProvenanceEntry(ProvenanceKind.OWNED, epoch, incarnationUlid, startOffset);
    }

    /// The entry as the log recorded it; a key this codec did not write is a failure, never a guess.
    public static Result<ProvenanceEntry> provenanceEntry(EpochStart start) {
        return ProvenanceEpoch.fromKey(start.key()).map(epoch -> provenanceEntry(epoch, start.startOffset()));
    }

    /// The epoch this entry names. A synthetic kind without its `d` (only a peer that is not this codec could send
    /// one) reads as an unattributed range with an empty `d`, which still equals no real epoch.
    public ProvenanceEpoch epoch() {
        return switch (kind) {
            case OWNED -> ProvenanceEpoch.owned(rank, id);
            case BASE -> new ProvenanceEpoch.Base(id.or(""), rank);
            case UNATTRIBUTED, UNKNOWN -> new ProvenanceEpoch.Unknown(id.or(""), rank);
        };
    }

    /// The log key of this entry's epoch; refused only for a token that cannot be framed in a key.
    public Result<EpochKey> key() {
        return EpochKey.epochKey(epoch().token());
    }

    private static boolean follows(EpochKey later, EpochKey earlier) {
        return Result.all(ProvenanceEpoch.fromKey(later),
                          ProvenanceEpoch.fromKey(earlier))
                     .map(ProvenanceEpoch::follows)
                     .or(false);
    }
}
