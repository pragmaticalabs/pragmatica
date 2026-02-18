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

import org.pragmatica.consensus.topology.TopologyChangeNotification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Listens for topology changes and updates the DHT ring accordingly.
/// Triggers data migration when partitions move between nodes.
public final class DHTTopologyListener {
    private static final Logger log = LoggerFactory.getLogger(DHTTopologyListener.class);

    private final DHTNode node;

    private DHTTopologyListener(DHTNode node) {
        this.node = node;
    }

    /// Create a topology listener for the given DHT node.
    ///
    /// @param node the local DHT node whose ring will be updated
    public static DHTTopologyListener dhtTopologyListener(DHTNode node) {
        return new DHTTopologyListener(node);
    }

    /// Handle a node-added event by adding the node to the consistent hash ring.
    public void onNodeAdded(TopologyChangeNotification.NodeAdded event) {
        var addedNodeId = event.nodeId();
        log.info("DHT: Node added {}, updating ring", addedNodeId.id());
        node.ring()
            .addNode(addedNodeId);
    }

    /// Handle a node-removed event by removing the node from the consistent hash ring.
    public void onNodeRemoved(TopologyChangeNotification.NodeRemoved event) {
        var removedNodeId = event.nodeId();
        log.info("DHT: Node removed {}, updating ring", removedNodeId.id());
        node.ring()
            .removeNode(removedNodeId);
    }
}
