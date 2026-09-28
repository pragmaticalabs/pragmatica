// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.provenance;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.storage.AppendLog.EpochKey;
import org.pragmatica.utility.ULID;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.utils.Causes.cause;


/// What wrote a range of a partition log (#1596, spec #1569 §7.5.1-§7.5.2): a real owner epoch, or a synthetic
/// one that stands for a range whose writer is not known ([Unknown]) or that an operator declared as a new
/// start ([Base]). Equality is record equality, and it is PROVENANCE equality: a real epoch equals only the
/// same epoch with the same incarnation ULID; a synthetic one equals only itself -- the same `d` and floor --
/// so two copies agree over a synthetic range only when one decision produced both.
///
/// The log orders consecutive entries by [#rank]: a real epoch ranks as itself, a synthetic one as its `floor`,
/// the last real epoch before it. A real epoch follows a synthetic one when it is at least the floor (the
/// owner of the floor may keep writing after it); nothing follows an entry whose rank is higher.
///
/// Log key tokens: `o.<term>.<counter>[.<ulid>]`, `u.<d>.<term>.<counter>`, `b.<d>.<term>.<counter>`. A token
/// must carry EVERY component of [Epoch] -- a component missing from it makes two different epochs one key --
/// which `ProvenanceEntryTest` pins against the record's declared components.
///
/// Not a wire type itself: [ProvenanceEntry] carries it flattened (kind, rank, id). A record field typed by a
/// `@Codec` sealed interface is generated as a call to a parent codec the processor never emits (#1633), so the
/// flat form is what crosses the wire.
public sealed interface ProvenanceEpoch {
    String SEPARATOR = ".";
    /// The epoch the log orders this one by.
    Epoch rank();
    /// This epoch's log key token.
    String token();

    /// A committed owner epoch. `incarnationUlid` is the reserved per-incarnation ULID slot (#1625): minted at
    /// every incarnation mint and every restore once #1529 part 2 lands, compared for equality only and never
    /// ordered. Until it is populated two lineages CAN mint equal epochs, and their histories compare equal
    /// `[unverified: #1625 open until S2 mints the ULID]`.
    record Owned(Epoch epoch, Option<String> incarnationUlid) implements ProvenanceEpoch {
        @Override
        public Epoch rank() {
            return epoch;
        }

        @Override
        public String token() {
            return "o" + SEPARATOR + epochToken(epoch) + incarnationUlid.map(ulid -> SEPARATOR + ulid)
                                                                        .or("");
        }
    }

    /// A range whose writer is not known: records a node took from a place that carried no provenance. `d` is a
    /// fresh ULID per decision, so it equals no other copy's range -- a comparison covering it fails closed.
    record Unknown(String d, Epoch floor) implements ProvenanceEpoch {
        @Override
        public Epoch rank() {
            return floor;
        }

        @Override
        public String token() {
            return "u" + SEPARATOR + d + SEPARATOR + epochToken(floor);
        }
    }

    /// An operator-declared start (AD14 `accept-loss --empty`): the copy's base. Defined for AD7/AD14; nothing in
    /// this change records one.
    record Base(String d, Epoch floor) implements ProvenanceEpoch {
        @Override
        public Epoch rank() {
            return floor;
        }

        @Override
        public String token() {
            return "b" + SEPARATOR + d + SEPARATOR + epochToken(floor);
        }
    }

    static ProvenanceEpoch owned(Epoch epoch) {
        return new Owned(epoch, none());
    }

    static ProvenanceEpoch owned(Epoch epoch, Option<String> incarnationUlid) {
        return new Owned(epoch, incarnationUlid);
    }

    /// A fresh unknown range above `floor`, the last real epoch of the history it extends.
    static ProvenanceEpoch unknown(Epoch floor) {
        return new Unknown(ULID.ulid().encoded(),
                           floor);
    }

    /// Whether an entry of `later` may follow one of `earlier` in one history.
    static boolean follows(ProvenanceEpoch later, ProvenanceEpoch earlier) {
        return switch (later) {
            case Owned owned when earlier instanceof Owned previous -> owned.epoch().isStrictlyAfter(previous.epoch());
            case Owned owned -> owned.epoch().isAtLeast(earlier.rank());
            case Unknown _, Base _ -> !later.equals(earlier) && later.rank().isAtLeast(earlier.rank());
        };
    }

    static Result<ProvenanceEpoch> fromKey(EpochKey key) {
        var parts = key.token().split("\\.", -1);

        return switch (parts[0]) {
            case "o" -> ownedFrom(key, parts);
            case "u" -> syntheticFrom(key, parts).map(pair -> new Unknown(parts[1], pair));
            case "b" -> syntheticFrom(key, parts).map(pair -> new Base(parts[1], pair));
            default -> notAKey(key);
        };
    }

    private static String epochToken(Epoch epoch) {
        return epoch.rabiaTerm() + SEPARATOR + epoch.localCounter();
    }

    private static Result<ProvenanceEpoch> ownedFrom(EpochKey key, String[] parts) {
        return parts.length < 3 || parts.length > 4
               ? notAKey(key)
               : epochFrom(parts[1], parts[2]).map(epoch -> new Owned(epoch, ulidOf(parts)));
    }

    private static Result<Epoch> syntheticFrom(EpochKey key, String[] parts) {
        return parts.length != 4
               ? notAKey(key)
               : epochFrom(parts[2], parts[3]);
    }

    private static Option<String> ulidOf(String[] parts) {
        return parts.length == 4
               ? some(parts[3])
               : none();
    }

    private static Result<Epoch> epochFrom(String term, String counter) {
        return Result.all(number(term), number(counter)).map(Epoch::epoch);
    }

    private static Result<Long> number(String field) {
        return Number.parseLong(field).mapError(_ -> cause("Not a number in a provenance epoch key: '" + field + "'"));
    }

    private static <T> Result<T> notAKey(EpochKey key) {
        return cause("Not a provenance epoch key: '" + key.token() + "'").result();
    }
}
