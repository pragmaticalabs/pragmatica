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

import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Sealed hierarchy of node lifecycle actions that CDM and ControlLoop can issue.
/// Executed by NodeLifecycleManager, which delegates to the appropriate ComputeProvider calls.
public sealed interface NodeAction {
    record StartNode(ProvisionSpec spec) implements NodeAction{}

    record StopNode(NodeId nodeId) implements NodeAction{}

    record RestartNode(NodeId nodeId) implements NodeAction{}

    record MigrateSlices(NodeId sourceNode, Option<NodeId> targetNode) implements NodeAction{}
}
