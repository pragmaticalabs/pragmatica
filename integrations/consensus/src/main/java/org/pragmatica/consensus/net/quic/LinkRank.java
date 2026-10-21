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
package org.pragmatica.consensus.net.quic;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;


/// The rank of one physical link of a node pair: the connection generation its dialer chose, and the dialer's id.
///
/// Both ends of a pair learn the same two attributes of every link: the dialer knows them because it chose them,
/// the acceptor reads the generation from the dialer's CONTROL preamble ([PeerOpenedLaneRouter#controlPreamble])
/// and the dialer's id from the verified Hello. [#survivesOver] is therefore a total order over the links of one
/// pair that reads no clock and no receipt evidence, so two ends that see the same set of links keep the same one
/// whatever order the links reached them in (#2138; before it, each end decided from its own phase age and receipt
/// clock, and under load the two ends disagreed).
///
/// A link without a rank (a dialer that sent no generation, a malformed one) is arbitrated by the legacy rule in
/// [PeerState] — that pair keeps today's window of disagreement.
record LinkRank(long generation, NodeId dialer) {
    /// Largest generation a link may claim. The headroom above it keeps the receiving end's own
    /// `highest + 1` from overflowing when a peer claims an enormous number: a claim above it means "no rank".
    static final long MAX_GENERATION = Long.MAX_VALUE - (1L << 20);

    /// The rank of a link dialed by `dialer` with `generation`. Empty when the link has no dialer or the
    /// generation is outside `[1, MAX_GENERATION]` (zero is the "no generation" value of a legacy dialer).
    static Option<LinkRank> linkRank(Option<NodeId> dialer, long generation) {
        return isValidGeneration(generation)
               ? dialer.map(id -> new LinkRank(generation, id))
               : none();
    }

    static boolean isValidGeneration(long generation) {
        return generation >= 1 && generation <= MAX_GENERATION;
    }

    /// True when this link wins an arbitration against `other`: the greater generation, and on equal generations
    /// the link dialed by the lower id. Distinct links of one pair never tie: one dialer never reuses a generation
    /// ([PeerState#nextDialGeneration]), and two dialers differ by id.
    boolean survivesOver(LinkRank other) {
        return generation != other.generation
               ? generation > other.generation
               : dialer.compareTo(other.dialer) < 0;
    }
}
