// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;


public interface ComputeProvider {
    Promise<InstanceInfo> provision(InstanceType instanceType);
    Promise<Unit> terminate(InstanceId instanceId);
    Promise<List<InstanceInfo>> listInstances();
    Promise<InstanceInfo> instanceStatus(InstanceId instanceId);

    default Promise<Unit> restart(InstanceId id) {
        return EnvironmentError.operationNotSupported("restart").promise();
    }

    default Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return EnvironmentError.operationNotSupported("applyTags").promise();
    }

    default Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return listInstances().map(instances -> filterByTags(instances, tagFilter));
    }

    default Promise<InstanceInfo> provision(ProvisionSpec spec) {
        return provision(spec.instanceType());
    }

    default Promise<List<InstanceInfo>> listInstances(TagSelector selector) {
        return listInstances(selector.requiredTags());
    }

    private static List<InstanceInfo> filterByTags(List<InstanceInfo> instances, Map<String, String> tagFilter) {
        return instances.stream().filter(instance -> matchesTags(instance, tagFilter))
                               .toList();
    }

    private static boolean matchesTags(InstanceInfo instance, Map<String, String> tagFilter) {
        return tagFilter.entrySet().stream()
                                 .allMatch(entry -> entry.getValue().equals(instance.tags().get(entry.getKey())));
    }
}
