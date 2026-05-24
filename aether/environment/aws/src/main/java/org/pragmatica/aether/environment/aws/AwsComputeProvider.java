// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.aws;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse;
import org.pragmatica.cloud.aws.api.Instance;
import org.pragmatica.cloud.aws.api.RunInstancesRequest;
import org.pragmatica.cloud.aws.api.RunInstancesResponse;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.IdGenerator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public record AwsComputeProvider(AwsClient client, AwsEnvironmentConfig config) implements ComputeProvider {
    private static final Logger log = LoggerFactory.getLogger(AwsComputeProvider.class);

    private static final String MANAGED_TAG_KEY = "aether-managed";

    private static final String MANAGED_TAG_VALUE = "true";

    private static final String NODE_ID_TAG = "aether-node-id";

    public static Result<AwsComputeProvider> awsComputeProvider(AwsClient client, AwsEnvironmentConfig config) {
        return success(new AwsComputeProvider(client, config));
    }

    @Override public Promise<InstanceInfo> provision(InstanceType instanceType) {
        return client.runInstances(buildRunRequest(Option.empty(),
                                                   config.userData())).flatMap(response -> tagAndMapFirstInstance(response,
                                                                                                                  defaultTags()))
                                  .mapError(AwsComputeProvider::toProvisionError);
    }
    @Override public Promise<InstanceInfo> provision(ProvisionSpec spec) {
        var zone = extractAvailabilityZone(spec.placement());
        var userData = spec.userData().or(config.userData());
        var tags = tagsFor(spec.context());
        return client.runInstances(buildRunRequest(zone, userData)).flatMap(response -> tagAndMapFirstInstance(response,
                                                                                                               tags))
                                  .mapError(AwsComputeProvider::toProvisionError);
    }

    @Override public Promise<Unit> terminate(InstanceId instanceId) {
        return client.terminateInstances(List.of(instanceId.value()))
                                        .mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override public Promise<List<InstanceInfo>> listInstances() {
        return client.describeInstances().map(AwsComputeProvider::toInstanceInfoList)
                                       .mapError(AwsComputeProvider::toListInstancesError);
    }

    @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return tagFilter.entrySet().stream()
                                 .findFirst()
                                 .map(entry -> describeByTag(entry.getKey(),
                                                             entry.getValue()))
                                 .orElseGet(this::listInstances);
    }

    @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        return client.describeInstances("instance-id",
                                        instanceId.value()).map(AwsComputeProvider::firstInstanceOrThrow)
                                       .mapError(AwsComputeProvider::toProvisionError);
    }

    @Override public Promise<Unit> restart(InstanceId id) {
        return client.rebootInstances(List.of(id.value()));
    }

    @Override public Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return client.createTags(List.of(id.value()),
                                 tags);
    }

    private Promise<InstanceInfo> tagAndMapFirstInstance(RunInstancesResponse response, Map<String, String> tags) {
        var instance = response.instances().getFirst();
        var instanceId = instance.instanceId();
        return client.createTags(List.of(instanceId), tags)
                     .onFailure(cause -> rollbackPartialInstance(instanceId, cause))
                     .map(unit -> toInstanceInfo(instance));
    }

    /// Rollback hook for partial provisions. `runInstances` succeeded (we have an
    /// instance ID) but the post-create `createTags` call failed. Without rollback
    /// the EC2 instance lingers untagged, unobserved by sweepers that filter on
    /// `aether-managed=true`, and continues to accrue cost. Issue an asynchronous
    /// `terminateInstances` against the orphan; log+swallow any rollback failure
    /// so the original create-flow Cause is what callers see.
    private void rollbackPartialInstance(String instanceId, Cause cause) {
        log.warn("Provision failed for AWS instance {} after runInstances succeeded ({}); attempting rollback via terminateInstances",
                 instanceId,
                 cause.message());
        client.terminateInstances(List.of(instanceId))
              .onFailure(rollbackCause -> log.warn("Rollback terminateInstances for {} failed: {}",
                                                    instanceId,
                                                    rollbackCause.message()))
              .onSuccess(ignored -> log.info("Rollback terminated partial AWS instance {}", instanceId));
    }

    private static Map<String, String> defaultTags() {
        return Map.of(MANAGED_TAG_KEY, MANAGED_TAG_VALUE,
                      NODE_ID_TAG, IdGenerator.generate("aether-node"));
    }

    private static Map<String, String> tagsFor(ProvisionContext ctx) {
        var tags = new java.util.HashMap<String, String>();
        tags.put(MANAGED_TAG_KEY, MANAGED_TAG_VALUE);
        var clusterName = resolveClusterName(ctx);
        if (!clusterName.isEmpty()) {tags.put("aether-cluster", clusterName);}
        if (!ctx.role().isEmpty()) {tags.put("aether-role", ctx.role());}
        if (!ctx.sourceName().isEmpty()) {tags.put("aether-source", ctx.sourceName());}
        tags.put(NODE_ID_TAG, ctx.resolveNodeId());
        tags.putAll(ctx.extraTags());
        return Map.copyOf(tags);
    }

    private static String resolveClusterName(ProvisionContext ctx) {
        if (!ctx.clusterName().isEmpty()) {return ctx.clusterName();}
        var fromEnv = System.getenv("AETHER_CLUSTER_NAME");
        return fromEnv != null && !fromEnv.isEmpty() ? fromEnv : "";
    }

    private Promise<List<InstanceInfo>> describeByTag(String tagKey, String tagValue) {
        return client.describeInstances(tagKey, tagValue).map(AwsComputeProvider::toInstanceInfoList)
                                       .mapError(AwsComputeProvider::toListInstancesError);
    }

    private RunInstancesRequest buildRunRequest(Option<String> availabilityZone, String userData) {
        return RunInstancesRequest.runInstancesRequest(config.amiId(),
                                                       config.instanceType(),
                                                       1,
                                                       1,
                                                       config.keyName(),
                                                       config.securityGroupIds(),
                                                       Option.some(config.subnetId()),
                                                       Option.some(userData),
                                                       availabilityZone);
    }

    private static Option<String> extractAvailabilityZone(Option<PlacementHint> placement) {
        return placement.flatMap(AwsComputeProvider::zoneFromHint);
    }

    private static Option<String> zoneFromHint(PlacementHint hint) {
        return switch (hint){
            case PlacementHint.ZoneHint zone -> Option.some(zone.zoneName());
            case PlacementHint.HostGroupHint ignored -> logUnsupported("HostGroupHint");
            case PlacementHint.AffinityHint ignored -> logUnsupported("AffinityHint");
            case PlacementHint.AntiAffinityHint ignored -> logUnsupported("AntiAffinityHint");
        };
    }

    private static Option<String> logUnsupported(String hintType) {
        log.debug("AWS provider ignoring {} — not yet supported", hintType);
        return Option.empty();
    }

    static InstanceInfo toInstanceInfo(Instance instance) {
        var tags = extractTags(instance);
        return new InstanceInfo(new InstanceId(instance.instanceId()),
                                mapStatus(instance.instanceState().name()),
                                collectAddresses(instance),
                                InstanceType.ON_DEMAND,
                                tags,
                                Option.option(tags.get(NODE_ID_TAG)));
    }

    private static InstanceInfo firstInstanceOrThrow(DescribeInstancesResponse response) {
        return toInstanceInfo(response.allInstances().getFirst());
    }

    private static List<InstanceInfo> toInstanceInfoList(DescribeInstancesResponse response) {
        return response.allInstances().stream()
                                    .map(AwsComputeProvider::toInstanceInfo)
                                    .toList();
    }

    static InstanceStatus mapStatus(String ec2Status) {
        return switch (ec2Status){
            case "pending" -> InstanceStatus.PROVISIONING;
            case "running" -> InstanceStatus.RUNNING;
            case "stopping", "stopped" -> InstanceStatus.STOPPING;
            case "shutting-down", "terminated" -> InstanceStatus.TERMINATED;
            default -> InstanceStatus.TERMINATED;
        };
    }

    static List<String> collectAddresses(Instance instance) {
        var publicIp = option(instance.publicIpAddress());
        var privateIp = option(instance.privateIpAddress());
        return Stream.concat(publicIp.stream(), privateIp.stream()).toList();
    }

    static Map<String, String> extractTags(Instance instance) {
        return option(instance.tagSet()).map(Instance.TagSet::items)
                     .map(AwsComputeProvider::tagsToMap)
                     .or(Map.of());
    }

    private static Map<String, String> tagsToMap(List<Instance.Tag> tags) {
        return tags.stream().collect(Collectors.toMap(Instance.Tag::key, Instance.Tag::value));
    }

    private static EnvironmentError toProvisionError(Cause cause) {
        return EnvironmentError.provisionFailed(new RuntimeException(cause.message()));
    }

    private static EnvironmentError toTerminateError(InstanceId instanceId, Cause cause) {
        return EnvironmentError.terminateFailed(instanceId, new RuntimeException(cause.message()));
    }

    private static EnvironmentError toListInstancesError(Cause cause) {
        return EnvironmentError.listInstancesFailed(new RuntimeException(cause.message()));
    }
}
