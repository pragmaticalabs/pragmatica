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
package org.pragmatica.consensus.topology;

import java.util.List;

import org.pragmatica.consensus.NodeId;


/// The transport's current view of the connected peer set, ordered against the producer's observations (#3047).
///
/// `sequence` is taken from the same counter as the sequence of a view-carrying [`TransportObservation`], BEFORE the
/// view is read, so a consumer that fences on sequences orders this view exactly like an observation: it applies iff
/// it is newer than the last view the consumer applied.
///
/// @param topology connected peers plus this node, sorted
/// @param sequence the producer's order of this view among its view-carrying observations
public record TransportView(List<NodeId> topology, long sequence) {
    public static TransportView transportView(List<NodeId> topology, long sequence) {
        return new TransportView(List.copyOf(topology), sequence);
    }
}
