// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

/// The source-bound face of the REAL single-provider [NodeLifecycleManager] that the production `SourceNodeLifecycleManager` shows
/// [CapacityControlledLifecycle]: one fixed binding, every bound call answered by the real record over the model provider. Nothing here
/// decides anything; it only supplies the `sourceBinding` the capacity layer requires and routes the bound overloads to the plain ones.
record ModelLifecycle(NodeLifecycleManager delegate, SourceName source) implements NodeLifecycleManager {
    static final String BINDING = "model-binding";

    @Override
    public Promise<ActionResult> executeAction(NodeAction action) {
        return delegate.executeAction(action);
    }

    @Override
    public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
        return delegate.provisionNode(spec);
    }

    @Override
    public Promise<Unit> terminateNode(NodeId nodeId) {
        return delegate.terminateNode(nodeId);
    }

    @Override
    public boolean isCloudManaged() {
        return true;
    }

    @Override
    public Result<String> sourceBinding(SourceName requested) {
        return Result.success(BINDING);
    }

    @Override
    public Promise<InstanceInfo> provisionNode(ProvisionSpec spec, String expectedBinding) {
        return delegate.provisionNode(spec);
    }

    @Override
    public Promise<Unit> terminateNode(NodeId node, SourceName requested, String expectedBinding) {
        return delegate.terminateNode(node);
    }

    @Override
    public Promise<List<InstanceInfo>> instancesForNode(NodeId node) {
        return delegate.instancesForNode(node);
    }

    @Override
    public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName requested, String expectedBinding) {
        return delegate.instancesForNode(node);
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return delegate.listInstances(tagFilter);
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter, SourceName requested, String expectedBinding) {
        return delegate.listInstances(filter);
    }

    @Override
    public Promise<InstanceInfo> instanceStatus(InstanceId instanceId, SourceName requested) {
        return delegate.instanceStatus(instanceId, requested);
    }

    @Override
    public Option<SourceName> sourceOf(NodeId nodeId) {
        return Option.some(source);
    }
}
