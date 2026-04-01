/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
 *
 */

package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.consensus.NodeId;

/// Sealed hierarchy of outcomes from NodeLifecycleManager actions.
public sealed interface ActionResult {
    /// A new cloud instance was provisioned and is starting.
    record NodeStarted(InstanceInfo instance) implements ActionResult{}

    /// The cloud instance for the given node was terminated.
    record NodeStopped(NodeId nodeId) implements ActionResult{}

    /// The cloud instance for the given node was restarted.
    record NodeRestarted(NodeId nodeId) implements ActionResult{}

    /// Slices were migrated from the source node.
    record SlicesMigrated(NodeId source, int sliceCount) implements ActionResult{}
}
