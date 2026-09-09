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
package org.pragmatica.serialization;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Operator-visible reporting for enum ordinals this node cannot name (#964).
///
/// The `UNKNOWN` sentinel makes the value visible to the HANDLER. This makes the event visible to the
/// OPERATOR, whose question is "is this cluster running mixed codec versions" and who previously had
/// no signal of any kind — the decode threw, a `catch (Exception)` swallowed it, and the message
/// vanished.
///
/// Reported once per (enum, ordinal). Version skew is a steady state, not an incident: the sending
/// node keeps sending, so an unthrottled line would emit once per message on the cluster's
/// highest-frequency traffic and bury the disclosure it exists to make. The distinct-key cap bounds
/// the set against a peer emitting arbitrary ordinals — past it reporting stops rather than the map
/// growing without limit. The counter is NOT throttled, so the true rate stays readable even after
/// the log line has been said once.
final class UnknownEnumOrdinals {
    private static final Logger log = LoggerFactory.getLogger(UnknownEnumOrdinals.class);
    /// Bounds memory against a peer sending arbitrary ordinals. Distinct (enum, ordinal) pairs from a
    /// genuine version skew number in the tens; anything past this is not a rolling upgrade.
    private static final int DISTINCT_KEY_LIMIT = 256;
    private static final Set<String> reportedKeys = ConcurrentHashMap.newKeySet();
    private static final LongAdder occurrences = new LongAdder();

    private UnknownEnumOrdinals() {}

    /// Records the unknown ordinal and YIELDS THE SENTINEL, rather than returning void and leaving the
    /// caller to supply it.
    ///
    /// Shaped this way so the method has a value to return: a void helper here is a JBCT-RET-01
    /// violation, and annotating it away would leave the gate green over a standing violation — the
    /// same shape as every examined-nothing green result this ticket exists to remove.
    static <E extends Enum<E>> E reportAndFallBack(int ordinal, E[] values, E unknown) {
        var enumType = unknown.getDeclaringClass();

        occurrences.increment();
        if (reportedKeys.size() >= DISTINCT_KEY_LIMIT || !reportedKeys.add(enumType.getName() + '#' + ordinal)) {
            return unknown;
        }

        log.warn("Decoded ordinal {} for enum {}, which has {} constant(s) on this node."
                + " A peer is running a codec version this node does not know; the value was surfaced as"
                + " UNKNOWN and the rest of the message kept. Handlers on authorization and condemnation"
                + " paths refuse UNKNOWN. Further occurrences of this pair are not logged.",
                 ordinal,
                 enumType.getName(),
                 values.length);

        return unknown;
    }

    /// Total unknown-ordinal decodes since JVM start, across every enum. Untruncated by the log
    /// throttle, so it answers "how much of our traffic is version-skewed" rather than "did it ever
    /// happen".
    static long occurrenceCount() {
        return occurrences.sum();
    }

    /// Distinct (enum, ordinal) pairs seen, capped at [#DISTINCT_KEY_LIMIT].
    static int distinctKeyCount() {
        return reportedKeys.size();
    }
}
