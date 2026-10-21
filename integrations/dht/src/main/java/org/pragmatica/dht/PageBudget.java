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
package org.pragmatica.dht;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

import org.pragmatica.consensus.net.OutboundMessageLimit;
import org.pragmatica.dht.storage.KeyStamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


/// The byte budget of one repair message (A6): a migration page, a survivor-rebalance push or a departure push
/// carries entries whose transfer size ([KeyStamp#transferBytes]) adds up to at most [#entryBytes], leaving
/// [#ENVELOPE_RESERVE_BYTES] for the message's own fields. The budget is always below the cluster frame
/// ([OutboundMessageLimit#MAX_TRANSFER_BYTES]), so a partition of any size moves in messages the transport accepts.
///
/// One entry larger than the budget travels alone in its own page: a value cannot be split, and every stored value
/// already passed the same frame bound when it was written.
public record PageBudget(long bytes) {
    /// Default budget: 16 MiB per message (spec A3b).
    public static final long DEFAULT_BYTES = 16L * 1024 * 1024;
    /// Space kept for the message's own fields: correlation id, sender, and the departure push's node lists.
    public static final long ENVELOPE_RESERVE_BYTES = 64L * 1024;
    /// Smallest budget accepted: room for the envelope plus useful payload.
    public static final long MIN_BYTES = 256L * 1024;
    /// Largest budget accepted: 1 MiB below the bounded transfer, never at it.
    public static final long MAX_BYTES = OutboundMessageLimit.MAX_TRANSFER_BYTES - 1024L * 1024;
    public static final PageBudget DEFAULT = new PageBudget(DEFAULT_BYTES);
    private static final Comparator<byte[]> KEY_ORDER = Arrays::compareUnsigned;

    private static final Cause OUT_OF_RANGE = Causes.cause("Repair page budget must be between " + MIN_BYTES
                                                          + " and " + MAX_BYTES
                                                          + " bytes");

    /// A validated budget.
    public static Result<PageBudget> pageBudget(long bytes) {
        return Verify.ensure(bytes, Verify.Is::between, MIN_BYTES, MAX_BYTES, OUT_OF_RANGE).map(PageBudget::new);
    }

    /// The bytes entries may take in one message.
    public long entryBytes() {
        return bytes - ENVELOPE_RESERVE_BYTES;
    }

    /// The order every page walks keys in: unsigned lexicographic, the order a resume cursor refers to.
    public static Comparator<byte[]> keyOrder() {
        return KEY_ORDER;
    }

    /// `stamps` in key order.
    public static List<KeyStamp> sorted(List<KeyStamp> stamps) {
        return stamps.stream()
                     .sorted(Comparator.comparing(KeyStamp::key, KEY_ORDER))
                     .toList();
    }

    /// Split `stamps` into consecutive pages in key order, each within [#entryBytes] (an oversized entry alone).
    public List<List<KeyStamp>> pages(List<KeyStamp> stamps) {
        return Stream.iterate(sorted(stamps),
                              remaining -> !remaining.isEmpty(),
                              this::afterLeadingRun)
                     .map(this::leadingRun)
                     .toList();
    }

    private List<KeyStamp> afterLeadingRun(List<KeyStamp> remaining) {
        return remaining.subList(leadingRun(remaining).size(), remaining.size());
    }

    /// The longest prefix of `sorted` within [#entryBytes]; never empty when `sorted` is not.
    public List<KeyStamp> leadingRun(List<KeyStamp> sorted) {
        return List.copyOf(sorted.subList(0, fittingCount(sorted, KeyStamp::transferBytes)));
    }

    /// The longest prefix of `entries` within [#entryBytes]; never empty when `entries` is not. Applied to the
    /// entries actually read for a page, which may have grown since their stamps were taken.
    public List<DHTMessage.KeyValue> fit(List<DHTMessage.KeyValue> entries) {
        return List.copyOf(entries.subList(0, fittingCount(entries, PageBudget::transferBytes)));
    }

    /// How many leading items fit within [#entryBytes] — at least one, so an oversized item travels alone.
    private <T> int fittingCount(List<T> items, ToLongFunction<T> size) {
        var total = 0L;
        var count = 0;

        for (var item : items) {
            total += size.applyAsLong(item);
            if (total > entryBytes()) {
                break;
            }

            count++;
        }

        return Math.min(items.size(), Math.max(1, count));
    }

    /// The bytes an entry costs in a page, by the same measure as [KeyStamp#transferBytes].
    public static long transferBytes(DHTMessage.KeyValue entry) {
        return (long) entry.key().length + entry.value().length + KeyStamp.ENTRY_OVERHEAD_BYTES;
    }

    /// The transfer size of a batch of entries.
    public static long transferBytes(List<DHTMessage.KeyValue> entries) {
        return entries.stream()
                      .mapToLong(PageBudget::transferBytes)
                      .sum();
    }
}
