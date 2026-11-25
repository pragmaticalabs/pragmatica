// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.util.function.LongUnaryOperator;

import org.pragmatica.aether.slice.RetentionDeclaration;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The floors a stream declaration is held to at parse, as pure arithmetic (stream-storage-design 2.2, 3.2): the hot
/// window against the seal runs, and the replay-window share `s(p)` against the seal and ring constants. The comparison
/// constants are [DeclarationDefaults]' seal mirrors; the factor 2 ("two units, so a window always holds more than the
/// unit it is cut in") lives here and nowhere else, the same factor for every floor.
///
/// SHARE. `s(p)` is the per-partition byte share over every bound computable at parse: `max_bytes / P`, and `max_count`
/// through `max_event_size` as `(max_count / P) x max_event_size`. Under `mode = "any"` it is the smallest of them (any one
/// computable bound is an upper bound); under `all` it is the largest, and an age bound makes it not computable (it needs
/// the ingress rate), so no share exists and the byte and event arms are skipped. Products saturate at `Long.MAX_VALUE`,
/// never throw.
///
/// ARMS, in the order [#shareRefusal] checks them: byte (`s(p) < 2 x seal_merge_bytes`), count (`max_count / P < 2 x
/// seal_run_events`, a record count, whatever the mode and whether or not an age bound is declared), event (`s(p) <
/// max_event_size`). The first failing arm is the refusal; each names the bound that sets the share and the smallest
/// value of that bound that clears the arm for this `P`.
public sealed interface DeclarationBounds {
    /// The factor over the unit a window must reach: two seal runs for the hot window, two merged segments or two seal
    /// runs' records for a retention share.
    long FACTOR = 2L;

    /// The three share checks.
    enum Arm {
        /// The share in bytes against two merged segments.
        BYTE,
        /// `max_count / P` in records against two seal runs' records.
        COUNT,
        /// The share in bytes against one maximal event.
        EVENT
    }

    /// A refused share: `setBy` is the `retention` key whose value sets the share (for the count arm, the count itself),
    /// `share` and `minimum` are in the arm's unit (bytes, or records for the count arm), `smallestValid` is the smallest
    /// value of `setBy` that clears the arm for this partition count.
    record ShareRefusal(Arm arm, String setBy, long share, long minimum, long smallestValid) {
        static ShareRefusal shareRefusal(Arm arm, String setBy, long share, long minimum, long smallestValid) {
            return new ShareRefusal(arm, setBy, share, minimum, smallestValid);
        }
    }

    /// One computable bound: the key, the byte share it gives, and the smallest value of the key whose share reaches a
    /// given number of bytes.
    record Bound(String key, long share, LongUnaryOperator smallestFor) {
        static Bound bound(String key, long share, LongUnaryOperator smallestFor) {
            return new Bound(key, share, smallestFor);
        }
    }

    /// The smallest `hot.max_bytes` that clears `HotWindowBelowSealRuns`: two seal runs of bytes.
    static long minHotBytes() {
        return saturatingMultiply(FACTOR, DeclarationDefaults.SEAL_RUN_BYTES);
    }

    /// The smallest `hot.max_count` that clears `HotWindowBelowSealRuns`: two seal runs of events.
    static long minHotCount() {
        return saturatingMultiply(FACTOR, DeclarationDefaults.SEAL_RUN_EVENTS);
    }

    /// The smallest share in bytes that clears the byte arm: two merged segments.
    static long minShareBytes() {
        return saturatingMultiply(FACTOR, DeclarationDefaults.SEAL_MERGE_BYTES);
    }

    /// The smallest `max_count / P` that clears the count arm: two seal runs' records.
    static long minShareCount() {
        return saturatingMultiply(FACTOR, DeclarationDefaults.SEAL_RUN_EVENTS);
    }

    /// The first failing share arm of `retention` for a stream of `partitions` partitions and events up to `maxEventSize`
    /// bytes, in the order byte, count, event; empty when every arm clears. A declaration with no computable share and no
    /// `max_count` (an age bound alone, or `all` with an age bound and no count) is never refused here.
    static Option<ShareRefusal> shareRefusal(RetentionDeclaration retention, int partitions, long maxEventSize) {
        var setting = settingBound(retention, partitions, maxEventSize);

        return byteArm(setting).orElse(() -> countArm(retention, partitions))
                               .orElse(() -> eventArm(setting, maxEventSize));
    }

    private static Option<ShareRefusal> byteArm(Option<Bound> setting) {
        return setting.filter(bound -> bound.share() < minShareBytes())
                      .map(bound -> refusedAt(Arm.BYTE,
                                              bound,
                                              minShareBytes()));
    }

    private static Option<ShareRefusal> countArm(RetentionDeclaration retention, int partitions) {
        return retention.maxCount()
                        .filter(count -> perPartition(count, partitions) < minShareCount())
                        .map(count -> ShareRefusal.shareRefusal(Arm.COUNT,
                                                                "retention.max_count",
                                                                perPartition(count, partitions),
                                                                minShareCount(),
                                                                saturatingMultiply(partitions, minShareCount())));
    }

    private static Option<ShareRefusal> eventArm(Option<Bound> setting, long maxEventSize) {
        return setting.filter(bound -> bound.share() < maxEventSize)
                      .map(bound -> refusedAt(Arm.EVENT,
                                              bound,
                                              maxEventSize));
    }

    private static ShareRefusal refusedAt(Arm arm, Bound bound, long minimum) {
        return ShareRefusal.shareRefusal(arm,
                                         bound.key(),
                                         bound.share(),
                                         minimum,
                                         bound.smallestFor()
                                              .applyAsLong(minimum));
    }

    /// The bound that sets `s(p)`: the smallest computable one under `any`, the largest under `all`, none when `all` has an
    /// age bound (not computable) or no byte or count bound is declared. A tie goes to `max_bytes`.
    private static Option<Bound> settingBound(RetentionDeclaration retention, int partitions, long maxEventSize) {
        var largest = retention.mode()
                               .or(DeclarationDefaults.DEFAULT_MODE) == RetentionMode.ALL;
        var bytes = retention.maxBytes()
                             .map(total -> byteBound(total, partitions));
        var count = retention.maxCount()
                             .map(total -> countBound(total, partitions, maxEventSize));

        return largest && retention.maxAgeMs()
                                   .isPresent()
               ? none()
               : bytes.flatMap(first -> some(count.fold(() -> first,
                                                        second -> chosen(first, second, largest))))
                      .orElse(count);
    }

    private static Bound chosen(Bound bytes, Bound count, boolean largest) {
        var countWins = largest
                        ? count.share() > bytes.share()
                        : count.share() < bytes.share();

        return countWins
               ? count
               : bytes;
    }

    private static Bound byteBound(long maxBytes, int partitions) {
        return Bound.bound("retention.max_bytes",
                           perPartition(maxBytes, partitions),
                           target -> saturatingMultiply(partitions, target));
    }

    private static Bound countBound(long maxCount, int partitions, long maxEventSize) {
        var eventBytes = Math.max(1L, maxEventSize);

        return Bound.bound("retention.max_count",
                           saturatingMultiply(perPartition(maxCount, partitions), eventBytes),
                           target -> saturatingMultiply(partitions,
                                                        Math.ceilDiv(target, eventBytes)));
    }

    private static long perPartition(long whole, int partitions) {
        return whole / Math.max(1, partitions);
    }

    /// `a x b` for non-negative operands, saturating at `Long.MAX_VALUE` instead of overflowing.
    private static long saturatingMultiply(long a, long b) {
        return a != 0 && b > Long.MAX_VALUE / a
               ? Long.MAX_VALUE
               : a * b;
    }

    record unused() implements DeclarationBounds {}
}
