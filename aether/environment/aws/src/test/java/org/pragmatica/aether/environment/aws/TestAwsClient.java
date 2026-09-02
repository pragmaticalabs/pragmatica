// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.environment.aws;

import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse.InstancesSet;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse.Reservation;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse.ReservationSet;
import org.pragmatica.cloud.aws.api.Instance;
import org.pragmatica.cloud.aws.api.RunInstancesResponse;
import org.pragmatica.cloud.aws.api.RunInstancesRequest;
import org.pragmatica.cloud.aws.api.SecurityGroup;
import org.pragmatica.cloud.aws.api.TargetHealth;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.Queue;

/// Test stub for AwsClient that returns canned responses and captures arguments.
final class TestAwsClient implements AwsClient {
    Promise<RunInstancesResponse> runInstancesResponse = Promise.success(emptyRunResponse());
    Promise<Unit> terminateResponse = Promise.success(Unit.unit());
    Promise<DescribeInstancesResponse> describeResponse = Promise.success(emptyDescribeResponse());
    Promise<Unit> rebootResponse = Promise.success(Unit.unit());
    Promise<Unit> createTagsResponse = Promise.success(Unit.unit());
    Promise<Unit> registerTargetsResponse = Promise.success(Unit.unit());
    Promise<Unit> deregisterTargetsResponse = Promise.success(Unit.unit());
    Promise<List<TargetHealth>> describeTargetHealthResponse = Promise.success(List.of());
    Promise<String> getSecretValueResponse = Promise.success("secret-value");
    Promise<String> createSecurityGroupResponse = Promise.success("sg-test");
    Promise<List<SecurityGroup>> describeSecurityGroupsResponse = Promise.success(List.of());
    Promise<Unit> authorizeIngressResponse = Promise.success(Unit.unit());
    Promise<Unit> revokeIngressResponse = Promise.success(Unit.unit());
    Promise<Unit> deleteSecurityGroupResponse = Promise.success(Unit.unit());
    Promise<Option<String>> vpcOfSubnetResponse = Promise.success(Option.none());
    Queue<Promise<String>> secretResponses;

    String lastVpcLookupSubnetId;
    List<String> lastTerminatedIds;
    List<String> lastRebootedIds;
    List<String> lastTagResourceIds;
    Map<String, String> lastTags;
    String lastDescribeTagKey;
    String lastDescribeTagValue;
    String lastDescribeByIdValue;
    String lastRegisterTargetGroupArn;
    List<String> lastRegisteredIds;
    String lastDeregisterTargetGroupArn;
    List<String> lastDeregisteredIds;
    String lastTargetHealthArn;
    String lastSecretId;
    RunInstancesRequest lastRunInstancesRequest;
    String lastCreatedGroupName;
    String lastCreatedGroupDescription;
    Option<String> lastCreatedGroupVpcId;
    Map<String, String> lastSecurityGroupTagFilters;
    String lastAuthorizeGroupId;
    String lastAuthorizeProtocol;
    int lastAuthorizePort;
    String lastAuthorizeCidr;
    String lastAuthorizeDescription;
    String lastRevokeGroupId;
    String lastRevokeProtocol;
    int lastRevokePort;
    String lastRevokeCidr;
    String lastRevokeDescription;
    String lastDeletedGroupId;

    @Override
    public Promise<RunInstancesResponse> runInstances(RunInstancesRequest request) {
        lastRunInstancesRequest = request;
        return runInstancesResponse;
    }

    @Override
    public Promise<Unit> terminateInstances(List<String> instanceIds) {
        lastTerminatedIds = instanceIds;
        return terminateResponse;
    }

    @Override
    public Promise<DescribeInstancesResponse> describeInstances() {
        return describeResponse;
    }

    @Override
    public Promise<DescribeInstancesResponse> describeInstances(String tagKey, String tagValue) {
        lastDescribeTagKey = tagKey;
        lastDescribeTagValue = tagValue;
        return describeResponse;
    }

    @Override
    public Promise<DescribeInstancesResponse> describeInstancesById(String instanceId) {
        lastDescribeByIdValue = instanceId;
        return describeResponse;
    }

    @Override
    public Promise<Unit> rebootInstances(List<String> instanceIds) {
        lastRebootedIds = instanceIds;
        return rebootResponse;
    }

    @Override
    public Promise<Unit> createTags(List<String> resourceIds, Map<String, String> tags) {
        lastTagResourceIds = resourceIds;
        lastTags = tags;
        return createTagsResponse;
    }

    @Override
    public Promise<String> createSecurityGroup(String name, String description, Option<String> vpcId) {
        lastCreatedGroupName = name;
        lastCreatedGroupDescription = description;
        lastCreatedGroupVpcId = vpcId;
        return createSecurityGroupResponse;
    }

    @Override
    public Promise<List<SecurityGroup>> describeSecurityGroups(Map<String, String> tagFilters) {
        lastSecurityGroupTagFilters = tagFilters;
        return describeSecurityGroupsResponse;
    }

    @Override
    public Promise<Unit> authorizeSecurityGroupIngress(String groupId,
                                                       String protocol,
                                                       int port,
                                                       String cidr,
                                                       String description) {
        lastAuthorizeGroupId = groupId;
        lastAuthorizeProtocol = protocol;
        lastAuthorizePort = port;
        lastAuthorizeCidr = cidr;
        lastAuthorizeDescription = description;
        return authorizeIngressResponse;
    }

    @Override
    public Promise<Unit> revokeSecurityGroupIngress(String groupId, String protocol, int port, String cidr, String description) {
        lastRevokeGroupId = groupId;
        lastRevokeProtocol = protocol;
        lastRevokePort = port;
        lastRevokeCidr = cidr;
        lastRevokeDescription = description;
        return revokeIngressResponse;
    }

    @Override
    public Promise<Unit> deleteSecurityGroup(String groupId) {
        lastDeletedGroupId = groupId;
        return deleteSecurityGroupResponse;
    }

    @Override
    public Promise<Option<String>> vpcOfSubnet(String subnetId) {
        lastVpcLookupSubnetId = subnetId;
        return vpcOfSubnetResponse;
    }

    @Override
    public Promise<Unit> registerTargets(String targetGroupArn, List<String> instanceIds) {
        lastRegisterTargetGroupArn = targetGroupArn;
        lastRegisteredIds = instanceIds;
        return registerTargetsResponse;
    }

    @Override
    public Promise<Unit> deregisterTargets(String targetGroupArn, List<String> instanceIds) {
        lastDeregisterTargetGroupArn = targetGroupArn;
        lastDeregisteredIds = instanceIds;
        return deregisterTargetsResponse;
    }

    @Override
    public Promise<List<TargetHealth>> describeTargetHealth(String targetGroupArn) {
        lastTargetHealthArn = targetGroupArn;
        return describeTargetHealthResponse;
    }

    @Override
    public Promise<String> getSecretValue(String secretId) {
        lastSecretId = secretId;
        if (secretResponses != null && !secretResponses.isEmpty()) {
            return secretResponses.poll();
        }
        return getSecretValueResponse;
    }

    // --- Factory helpers ---

    static DescribeInstancesResponse emptyDescribeResponse() {
        return new DescribeInstancesResponse(new ReservationSet(List.of()));
    }

    static DescribeInstancesResponse describeResponseWith(List<Instance> instances) {
        var reservation = new Reservation("r-12345", new InstancesSet(instances));
        return new DescribeInstancesResponse(new ReservationSet(List.of(reservation)));
    }

    static RunInstancesResponse emptyRunResponse() {
        return new RunInstancesResponse(new RunInstancesResponse.InstancesSet(List.of()));
    }

    static RunInstancesResponse runResponseWith(Instance instance) {
        return new RunInstancesResponse(new RunInstancesResponse.InstancesSet(List.of(instance)));
    }
}
