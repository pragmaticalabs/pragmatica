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
package org.pragmatica.consensus.net;

import java.util.Map;
import java.util.Set;

import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.SharedScheduler;


/// Wire-generation join guard (#2981 step 6): a node refuses to start when a core member that has already
/// completed its handshake advertises a LOWER wire generation, because an older node cannot decode the records
/// this node writes. The decision reads Hello labels only.
///
/// - Handshaken, no [NodeInfo#LABEL_WIRE_GENERATION] label: generation 1 (pre-guard), refused.
/// - Not yet handshaken: unknown, never "older" — the guard waits for it up to the wait bound, then proceeds,
///   deciding only over the members that did handshake. A cold stop-the-world start is therefore never refused.
/// - A label that is not a decimal integer counts as generation 1 (refuse: the safe failure direction) [guess].
///
/// Protects only the NEW node; an old binary rejoining an upgraded cluster is stopped only by the operator
/// procedure (`aether/upgrade.sh`).
public interface JoinGenerationGuard {
    /// This binary's wire generation. Bump on every wire/codec change that an older node cannot decode.
    int WIRE_GENERATION = 2;
    /// Bound on waiting for core members to handshake [guess: taste; a constant, not configuration].
    TimeSpan JOIN_GENERATION_WAIT = TimeSpan.timeSpan(10).seconds();
    /// Handshake poll cadence while waiting.
    TimeSpan POLL_INTERVAL = TimeSpan.timeSpan(50).millis();

    /// The wire generation a handshaken peer advertises: its label, or 1 when absent or unreadable.
    static int generationOf(Map<String, String> labels) {
        return Option.option(labels.get(NodeInfo.LABEL_WIRE_GENERATION))
                     .flatMap(JoinGenerationGuard::parseGeneration)
                     .or(1);
    }

    private static Option<Integer> parseGeneration(String raw) {
        return Number.parseInt(raw)
              .option();
    }

    /// Waits until every member in `members` has handshaken or `wait` elapses, whichever is first, failing at
    /// once with [ConsensusError.WireGenerationRefused] as soon as any handshaken member is LOWER than `own`.
    ///
    /// @param members  the decision set (core voters other than this node)
    /// @param labelsOf Hello labels per member, empty until that member has handshaken
    static Promise<Unit> admit(Set<NodeId> members,
                               Fn1<Option<Map<String, String>>, NodeId> labelsOf,
                               TimeSpan wait,
                               int own) {
        return poll(members,
                    labelsOf,
                    System.nanoTime() + wait.nanos(),
                    own,
                    Promise.promise());
    }

    private static Promise<Unit> poll(Set<NodeId> members,
                                      Fn1<Option<Map<String, String>>, NodeId> labelsOf,
                                      long deadlineNanos,
                                      int own,
                                      Promise<Unit> promise) {
        return firstLower(members, labelsOf, own).fold(() -> settleOrWait(members, labelsOf, deadlineNanos, own, promise),
                                                       promise::fail);
    }

    private static Promise<Unit> settleOrWait(Set<NodeId> members,
                                              Fn1<Option<Map<String, String>>, NodeId> labelsOf,
                                              long deadlineNanos,
                                              int own,
                                              Promise<Unit> promise) {
        var allHandshaken = members.stream().allMatch(member -> labelsOf.apply(member)
                                                                        .isPresent());

        return allHandshaken || System.nanoTime() - deadlineNanos >= 0
               ? promise.succeed(Unit.unit())
               : reschedule(members, labelsOf, deadlineNanos, own, promise);
    }

    private static Promise<Unit> reschedule(Set<NodeId> members,
                                            Fn1<Option<Map<String, String>>, NodeId> labelsOf,
                                            long deadlineNanos,
                                            int own,
                                            Promise<Unit> promise) {
        var _ = SharedScheduler.schedule(() -> poll(members, labelsOf, deadlineNanos, own, promise), POLL_INTERVAL);

        return promise;
    }

    private static Option<ConsensusError> firstLower(Set<NodeId> members,
                                                     Fn1<Option<Map<String, String>>, NodeId> labelsOf,
                                                     int own) {
        return Option.from(members.stream()
                                  .sorted()
                                  .flatMap(member -> refusalFor(member,
                                                                labelsOf.apply(member),
                                                                own).stream())
                                  .findFirst());
    }

    private static Option<ConsensusError> refusalFor(NodeId member, Option<Map<String, String>> labels, int own) {
        return labels.map(JoinGenerationGuard::generationOf)
                     .filter(generation -> generation < own)
                     .map(generation -> (ConsensusError) new ConsensusError.WireGenerationRefused(member,
                                                                                                  generation,
                                                                                                  own));
    }
}
