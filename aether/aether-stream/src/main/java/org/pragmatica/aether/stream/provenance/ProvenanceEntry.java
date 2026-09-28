// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.provenance;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Codec;
import org.pragmatica.storage.AppendLog.EpochKey;
import org.pragmatica.storage.AppendLog.EpochOrder;
import org.pragmatica.storage.AppendLog.EpochStart;


/// One entry of a partition log's owner-epoch history (#1596, spec #1569 §7.5.1): the committed owner epoch
/// `epoch` began writing the log at `startOffset`. The epoch is the one committed in
/// `StreamPartitionOwnershipValue` under which the record was first published, never the fence token and
/// never a receiver's local view.
///
/// `incarnationUlid` is the reserved slot for a per-incarnation ULID (#1625): minted at every incarnation mint
/// and every restore once #1529 part 2 lands, so two lineages that reuse an incarnation number never share
/// an epoch. It is compared for EQUALITY only ([#sameEpoch]) and never ordered. Until it is populated two
/// lineages CAN mint equal epochs, and two such histories compare prov-equal: a false CONSISTENT
/// `[unverified: #1625 open until S2 mints the ULID]`.
///
/// The log stores the epoch as an opaque [EpochKey]: `<rabiaTerm>.<localCounter>` or
/// `<rabiaTerm>.<localCounter>.<incarnationUlid>`. The key must carry EVERY component of [Epoch] — a
/// component missing from it makes two different epochs one key — which `ProvenanceEntryTest` pins against
/// the record's declared components.
@Codec
public record ProvenanceEntry(Epoch epoch, Option<String> incarnationUlid, long startOffset) {
    private static final String SEPARATOR = ".";

    /// The order the log enforces between consecutive entries: a strictly later [Epoch]. Two keys of the same
    /// epoch that differ only in the ULID follow neither way, so the log refuses the second (#1625).
    public static final EpochOrder ORDER = (later, earlier) -> Result.all(epochOf(later), epochOf(earlier))
                                                                      .map(Epoch::isStrictlyAfter)
                                                                      .or(false);

    public static ProvenanceEntry provenanceEntry(Epoch epoch, Option<String> incarnationUlid, long startOffset) {
        return new ProvenanceEntry(epoch, incarnationUlid, startOffset);
    }

    /// The entry as the log recorded it; a key this codec did not write is a failure, never a guess.
    public static Result<ProvenanceEntry> provenanceEntry(EpochStart start) {
        return decode(start.key()).map(entry -> entry.startingAt(start.startOffset()));
    }

    /// The log key of this entry's epoch; refused only for a ULID that cannot be framed in a key.
    public Result<EpochKey> key() {
        return EpochKey.epochKey(token(epoch, incarnationUlid));
    }

    /// Provenance equality of two epochs: the epoch and the incarnation ULID, never the start.
    public boolean sameEpoch(ProvenanceEntry other) {
        return epoch.equals(other.epoch) && incarnationUlid.equals(other.incarnationUlid);
    }

    private ProvenanceEntry startingAt(long offset) {
        return new ProvenanceEntry(epoch, incarnationUlid, offset);
    }

    private static String token(Epoch epoch, Option<String> incarnationUlid) {
        return epoch.rabiaTerm() + SEPARATOR + epoch.localCounter() + incarnationUlid.map(ulid -> SEPARATOR + ulid)
                                                                                     .or("");
    }

    private static Result<Epoch> epochOf(EpochKey key) {
        return decode(key).map(ProvenanceEntry::epoch);
    }

    private static Result<ProvenanceEntry> decode(EpochKey key) {
        var parts = key.token()
                       .split("\\.", -1);

        return parts.length < 2 || parts.length > 3
               ? Causes.cause("Not a provenance epoch key: '" + key.token() + "'").result()
               : Result.all(number(parts[0]), number(parts[1]))
                       .map(Epoch::epoch)
                       .map(epoch -> new ProvenanceEntry(epoch, ulidOf(parts), 0));
    }

    private static Option<String> ulidOf(String[] parts) {
        return parts.length == 3
               ? Option.some(parts[2])
               : Option.none();
    }

    private static Result<Long> number(String field) {
        return Result.lift(_ -> Causes.cause("Not a number in a provenance epoch key: '" + field + "'"),
                           () -> Long.parseLong(field));
    }
}
