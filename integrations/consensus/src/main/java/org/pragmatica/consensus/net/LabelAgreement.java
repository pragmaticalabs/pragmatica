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
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;


/// Label agreement of the join guard (#3525): the labels every core member must share, and the quorum that must have
/// handshaken before this node casts a ballot. The decision reads Hello labels only, and consensus names no label: the
/// assembly that stamps one into the Hello labels names it in `labels`.
///
/// - A handshaken peer whose value of any agreeing label differs from this node's is refused, symmetrically (the peer applies the
///   same rule to this node). An absent label differs from a present one; two absent labels agree.
/// - Not yet handshaken: unknown, never a mismatch. Unlike the wire-generation wait there is no deadline pass-through: the guard
///   admits only once `peersNeeded` core peers (a quorum, this node counted) have handshaken, because a node that has not seen a
///   quorum cannot know it agrees with one. Without a quorum there is no election, so waiting costs no availability; once the
///   wait bound has passed with the quorum incomplete, `onIncomplete` is told which peers are still missing, once per wait episode.
/// - No agreeing labels: nothing to agree on, so nothing to wait for ([#none]).
///
/// @param own          this node's labels
/// @param labels       the labels every core member must share
/// @param peersNeeded  how many other core peers must have handshaken (a quorum of the core, less this node)
/// @param onIncomplete told the core peers not yet handshaken; the engine's operator warning
public record LabelAgreement(Map<String, String> own,
                             Set<String> labels,
                             int peersNeeded,
                             Fn1<Unit, Set<NodeId>> onIncomplete) {
    private static final LabelAgreement NONE = new LabelAgreement(Map.of(), Set.of(), 0, _ -> Unit.unit());

    public LabelAgreement {
        own = Map.copyOf(own);
        labels = Set.copyOf(labels);
    }

    public static LabelAgreement labelAgreement(Map<String, String> own,
                                                Set<String> labels,
                                                int peersNeeded,
                                                Fn1<Unit, Set<NodeId>> onIncomplete) {
        return new LabelAgreement(own, labels, peersNeeded, onIncomplete);
    }

    /// Nothing to agree on: no label is compared and no quorum is awaited.
    public static LabelAgreement none() {
        return NONE;
    }

    /// This agreement bound to one wait episode: `onIncomplete` fires at most once, however many polls find the quorum incomplete.
    LabelAgreement episode() {
        var noticed = new AtomicBoolean();

        return new LabelAgreement(own, labels, peersNeeded, missing -> noticeOnce(noticed, onIncomplete, missing));
    }

    private static Unit noticeOnce(AtomicBoolean noticed, Fn1<Unit, Set<NodeId>> notice, Set<NodeId> missing) {
        return noticed.compareAndSet(false, true)
               ? notice.apply(missing)
               : Unit.unit();
    }

    /// The first handshaken member (in id order) whose value of an agreeing label differs from this node's.
    Option<ConsensusError> firstMismatch(Set<NodeId> members, Fn1<Option<Map<String, String>>, NodeId> labelsOf) {
        return Option.from(members.stream()
                                  .sorted()
                                  .flatMap(member -> mismatchOf(member,
                                                                labelsOf.apply(member)).stream())
                                  .findFirst());
    }

    /// Whether enough core peers have handshaken for this node to know it agrees with a quorum. Vacuous without agreeing labels.
    boolean reached(Set<NodeId> members, Fn1<Option<Map<String, String>>, NodeId> labelsOf) {
        return labels.isEmpty() || handshaken(members, labelsOf) >= peersNeeded;
    }

    /// Tells `onIncomplete` which members have not handshaken.
    Unit noticeIncomplete(Set<NodeId> members, Fn1<Option<Map<String, String>>, NodeId> labelsOf) {
        return onIncomplete.apply(members.stream()
                                         .filter(member -> labelsOf.apply(member).isEmpty())
                                         .collect(Collectors.toCollection(TreeSet::new)));
    }

    private long handshaken(Set<NodeId> members, Fn1<Option<Map<String, String>>, NodeId> labelsOf) {
        return members.stream()
                      .filter(member -> labelsOf.apply(member).isPresent())
                      .count();
    }

    private Option<ConsensusError> mismatchOf(NodeId member, Option<Map<String, String>> memberLabels) {
        return memberLabels.flatMap(handshaken -> firstDifferingLabel(member, handshaken));
    }

    private Option<ConsensusError> firstDifferingLabel(NodeId member, Map<String, String> memberLabels) {
        return Option.from(labels.stream()
                                 .sorted()
                                 .filter(label -> differs(label, memberLabels))
                                 .findFirst())
                     .map(label -> refusal(member, label, memberLabels));
    }

    private boolean differs(String label, Map<String, String> memberLabels) {
        return !Option.option(own.get(label)).equals(Option.option(memberLabels.get(label)));
    }

    private ConsensusError refusal(NodeId member, String label, Map<String, String> memberLabels) {
        return new ConsensusError.LabelAgreementRefused(member,
                                                        label,
                                                        Option.option(memberLabels.get(label)),
                                                        Option.option(own.get(label)));
    }
}
