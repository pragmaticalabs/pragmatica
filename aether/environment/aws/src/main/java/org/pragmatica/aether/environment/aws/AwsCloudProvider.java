// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.aws;

import java.util.List;

import org.pragmatica.aether.environment.CloudProvider;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.QuotaStatus;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public record AwsCloudProvider(ComputeProvider computeProvider) implements CloudProvider {
    public static AwsCloudProvider awsCloudProvider(ComputeProvider computeProvider) {
        return new AwsCloudProvider(computeProvider);
    }

    @Override
    public Promise<QuotaStatus> checkQuota(NodeGroupConfig group) {
        return Promise.success(QuotaStatus.unknown(group.count()));
    }

    @Override
    public Promise<List<ProvisionedNode>> provision(NodeGroupConfig group) {
        return CloudProviderSupport.provisionVia(computeProvider, group);
    }

    @Override
    public Promise<List<ProvisionedNode>> provisionSpot(NodeGroupConfig group) {
        return EnvironmentError.operationNotSupported("spot provisioning not implemented in v1 for AWS").promise();
    }

    @Override
    public Promise<Unit> destroy(List<String> nodeIds) {
        return CloudProviderSupport.destroyVia(computeProvider, nodeIds);
    }

    @Override
    public Promise<List<NodeAddress>> addresses(List<String> nodeIds) {
        return CloudProviderSupport.addressesVia(computeProvider, nodeIds);
    }

    @Override
    public boolean supportsPreemptible() {
        return true;
    }
}
