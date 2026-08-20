// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.aws;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.MarketOptions;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ReadinessPolicy;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.cloud.aws.AwsError;
import org.pragmatica.cloud.aws.api.SecurityGroup;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse;
import org.pragmatica.cloud.aws.api.Instance;
import org.pragmatica.cloud.aws.api.RunInstancesRequest;
import org.pragmatica.cloud.aws.api.RunInstancesResponse;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public record AwsComputeProvider(AwsClient client, AwsEnvironmentConfig config) implements ComputeProvider {
    private static final Logger log = LoggerFactory.getLogger(AwsComputeProvider.class);
    private static final String MANAGED_TAG_KEY = "aether-managed";
    private static final String MANAGED_TAG_VALUE = "true";
    private static final String NODE_ID_TAG = "aether-node-id";
    /// The `(cluster, source)` pair is BOTH the stamp put on every provisioned instance and the
    /// selector that finds a source's security group again. One constant per key so the two can
    /// never drift apart — a stamp that does not round-trip with its selector is the silent failure
    /// [SourceName] exists to prevent.
    static final String CLUSTER_TAG = "aether-cluster";
    static final String SOURCE_TAG = "aether-source";

    public static Result<AwsComputeProvider> awsComputeProvider(AwsClient client, AwsEnvironmentConfig config) {
        return success(new AwsComputeProvider(client, config));
    }

    @Override
    public ProviderDefaults providerDefaults() {
        return ProviderDefaults.providerDefaults(config.instanceType(),
                                                 config.amiId(),
                                                 "",
                                                 "",
                                                 option(config.userData()),
                                                 true);
    }

    /// Translate a fully-resolved [ProvisionRequest] into an EC2 RunInstances call. resolve() has
    /// applied the AMI (image) / instance-type / zone / user-data precedence, so this consumes those
    /// fields verbatim — catching AWS up to Hetzner's plumbing (it previously DROPPED the spec size
    /// and image, using `config.instanceType()`/`config.amiId()`). A SPOT market attaches EC2
    /// `InstanceMarketOptions` (RFC-0016 §2.5-ii).
    @Override
    public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
        var tags = tagsFor(request.context());

        return resolveSecurityGroupIds(request.context()).flatMap(groupIds -> client.runInstances(buildRunRequest(request,
                                                                                                                  groupIds)))
                                      .flatMap(response -> tagAndMapFirstInstance(response, tags))
                                      .flatMap(info -> confirmRunning(info,
                                                                      ReadinessPolicy.cloudDefault()))
                                      .mapError(AwsComputeProvider::toProvisionError);
    }

    @Override
    public Promise<Unit> terminate(InstanceId instanceId) {
        return client.terminateInstances(List.of(instanceId.value()))
                     .mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances() {
        return client.describeInstances()
                     .map(AwsComputeProvider::toInstanceInfoList)
                     .mapError(AwsComputeProvider::toListInstancesError);
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return tagFilter.entrySet()
                        .stream()
                        .findFirst()
                        .map(entry -> describeByTag(entry.getKey(),
                                                    entry.getValue()))
                        .orElseGet(this::listInstances);
    }

    @Override
    public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        return client.describeInstancesById(instanceId.value())
                     .flatMap(response -> firstInstance(response, instanceId))
                     .mapError(AwsComputeProvider::toProvisionError);
    }

    @Override
    public Promise<Unit> restart(InstanceId id) {
        return client.rebootInstances(List.of(id.value()));
    }

    @Override
    public Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return client.createTags(List.of(id.value()),
                                 tags);
    }

    private Promise<InstanceInfo> tagAndMapFirstInstance(RunInstancesResponse response, Map<String, String> tags) {
        return response.instances()
                       .stream()
                       .findFirst()
                       .map(instance -> tagAndMap(instance, tags))
                       .orElseGet(AwsComputeProvider::provisionReturnedNoInstances);
    }

    private Promise<InstanceInfo> tagAndMap(Instance instance, Map<String, String> tags) {
        var instanceId = instance.instanceId();

        return client.createTags(List.of(instanceId),
                                 tags)
                     .onFailure(cause -> rollbackPartialInstance(instanceId, cause))
                     .map(unit -> toInstanceInfo(instance));
    }

    private static Promise<InstanceInfo> provisionReturnedNoInstances() {
        return EnvironmentError.provisionFailed(new RuntimeException("RunInstances returned no instances")).promise();
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

    private static Map<String, String> tagsFor(ProvisionContext ctx) {
        var tags = new java.util.HashMap<String, String>();

        tags.put(MANAGED_TAG_KEY, MANAGED_TAG_VALUE);
        resolveClusterName(ctx).onPresent(name -> tags.put(CLUSTER_TAG, name.value()));
        if (!ctx.role().isEmpty()) {
            tags.put("aether-role", ctx.role());
        }
        // Unconditional: SourceName cannot be blank, so the historical emptiness guard here was dead.
        tags.put(SOURCE_TAG,
                 ctx.sourceName().value());
        tags.put(NODE_ID_TAG, ctx.resolveNodeId());
        tags.putAll(ctx.extraTags());

        return Map.copyOf(tags);
    }

    /// Resolution chain: the provisioning context, then `AETHER_CLUSTER_NAME` (the pre-bootstrap
    /// window). Ends [Option#empty] rather than at a placeholder — an unresolved cluster leaves the
    /// `aether-cluster` tag OFF, exactly as the historical empty string did, and never stamps a name
    /// a scoped cleanup sweep would then have to guess at. A value outside the RFC-1035 grammar in
    /// the env var reads as absent here rather than as a name no selector can match.
    private static Option<ClusterName> resolveClusterName(ProvisionContext ctx) {
        return ctx.clusterName()
                  .orElse(() -> ClusterName.maybeClusterName(System.getenv("AETHER_CLUSTER_NAME")));
    }

    private Promise<List<InstanceInfo>> describeByTag(String tagKey, String tagValue) {
        return client.describeInstances(tagKey, tagValue)
                     .map(AwsComputeProvider::toInstanceInfoList)
                     .mapError(AwsComputeProvider::toListInstancesError);
    }

    /// The security-group association for an instance being CREATED — the AWS counterpart of
    /// `HetznerComputeProvider.resolveFirewallIds`, and it exists for the same reason: a CTM
    /// auto-heal replacement is built from a `SourceProfile`, which persists ingress RULES but never
    /// the created group's id, so `config.securityGroupIds()` is empty on that path and the
    /// replacement would launch with none of the ingress its bootstrap peers have.
    ///
    /// Resolution is BY TAG, reusing the one-group-per-(cluster, source) selector [#openIngress]
    /// owns — deliberately not by persisting ids (they go stale the moment a group is recreated out
    /// of band) and not by re-creating from `SourceProfile.firewallRules` (that turns rule drift
    /// into a silent reconciliation).
    private Promise<List<String>> resolveSecurityGroupIds(ProvisionContext ctx) {
        var configured = config.securityGroupIds();

        if (!configured.isEmpty()) {
            return Promise.success(configured);
        }

        return resolveClusterName(ctx).fold(() -> securityGroupScopeUnresolved(ctx.sourceName()),
                                            cluster -> securityGroupsFor(cluster, ctx.sourceName()));
    }

    private Promise<List<String>> securityGroupsFor(ClusterName cluster, SourceName source) {
        return client.describeSecurityGroups(securityGroupSelector(cluster, source))
                     .map(AwsComputeProvider::idsOf)
                     .recover(cause -> securityGroupLookupUnavailable(cluster, source, cause))
                     .map(ids -> warnWhenNothingResolved(ids, cluster, source));
    }

    /// Selects the ONE security group belonging to this `(cluster, source)`. Both tags are required:
    /// scoping by cluster alone would match another source's group, and scoping by source alone would
    /// match another cluster's — either way `openIngress` would patch, and `cluster destroy` would
    /// delete, a group Aether did not create for this pair. The Hetzner analogue is `firewallSelector`,
    /// and the 2026-08-03 test-pg incident (#572) is what unscoped selection costs.
    ///
    /// Ordered so the emitted `Filter.N` numbering is reproducible; EC2 ANDs the filters and does not
    /// care about their order, but a stable order keeps request assertions legible.
    private static Map<String, String> securityGroupSelector(ClusterName cluster, SourceName source) {
        var filters = new LinkedHashMap<String, String>();

        filters.put(CLUSTER_TAG, cluster.value());
        filters.put(SOURCE_TAG, source.value());

        return filters;
    }

    private static List<String> idsOf(List<SecurityGroup> groups) {
        return groups.stream()
                     .map(SecurityGroup::groupId)
                     .toList();
    }

    /// ## The AWS fail policy is the INVERSE of Hetzner's, deliberately — do not harmonise them
    ///
    /// `HetznerComputeProvider` REFUSES to provision when no firewall resolves, because a Hetzner
    /// server with no firewall association accepts ALL inbound traffic (§6.2): there, an unresolved
    /// firewall means a publicly reachable node.
    ///
    /// EC2 security groups default-DENY. An instance launched with no Aether group attached is
    /// unreachable, not exposed — the failure is a node that cannot join, which is visible and
    /// self-correcting, rather than a silent security hole. Refusing here would therefore kill
    /// auto-heal permanently to prevent an exposure that cannot occur on this provider. So: WARN
    /// loudly, naming the cluster, the source and what was not attached, and proceed.
    ///
    /// The same reasoning covers a FAILED lookup (see [#securityGroupLookupUnavailable]), which is
    /// the second place the two providers diverge: unknown state is not evidence of a safe one on
    /// Hetzner, but on AWS there is no unsafe state for it to hide.
    private static List<String> warnWhenNothingResolved(List<String> ids, ClusterName cluster, SourceName source) {
        if (ids.isEmpty()) {
            log.warn("AWS provision: no Aether-managed security group is tagged {}={},{}={} — the instance is created "
                    + "with NO Aether ingress attached and will be UNREACHABLE on every port those rules would have "
                    + "opened (EC2 security groups default-deny, so this is an availability failure, not an exposure). "
                    + "Declare `[source.{}.firewall] allow_ingress` so bootstrap creates the group, or set "
                    + "`[cloud.compute] security_group_ids`.",
                     CLUSTER_TAG,
                     cluster,
                     SOURCE_TAG,
                     source,
                     source);
        }

        return ids;
    }

    /// FER — degrade-and-continue. The lookup failure is absorbed rather than propagated, and the
    /// guarantee that earns is exactly the one above: the instance is created UNREACHABLE instead of
    /// not at all, so auto-heal keeps making progress. Mechanism: a single attempt, no retry — a
    /// transient DescribeSecurityGroups failure yields one instance with no Aether ingress, which
    /// the next reconciler pass replaces once the lookup works again.
    private static List<String> securityGroupLookupUnavailable(ClusterName cluster, SourceName source, Cause cause) {
        log.warn("AWS provision: security-group lookup for {}={},{}={} FAILED ({}); the instance is created with NO "
                + "Aether ingress attached and will be UNREACHABLE. Proceeding rather than refusing — an EC2 instance "
                + "without a group is denied inbound, so unlike Hetzner there is no exposure to guard against.",
                 CLUSTER_TAG,
                 cluster,
                 SOURCE_TAG,
                 source,
                 cause.message());

        return List.of();
    }

    /// A group lookup is `(cluster, source)`-scoped, so with no cluster name there is no selector to
    /// run. AWS does not refuse the create for this (see [#warnWhenNothingResolved]); it warns and
    /// launches with whatever EC2 defaults to, which is the account's default security group.
    private static Promise<List<String>> securityGroupScopeUnresolved(SourceName source) {
        log.warn("AWS provision: cluster name unresolved (absent from the provisioning context and from "
                + "AETHER_CLUSTER_NAME), so no {}/{} selector exists for source '{}' — the instance is created with NO "
                + "Aether ingress attached and will be UNREACHABLE.",
                 CLUSTER_TAG,
                 SOURCE_TAG,
                 source);

        return Promise.success(List.of());
    }

    private RunInstancesRequest buildRunRequest(ProvisionRequest request, List<String> securityGroupIds) {
        var base = RunInstancesRequest.runInstancesRequest(request.image(),
                                                           request.instanceSize(),
                                                           1,
                                                           1,
                                                           config.keyName(),
                                                           securityGroupIds,
                                                           Option.some(config.subnetId()),
                                                           Option.some(request.userData().or("")),
                                                           zoneOption(request.zone()));

        return request.market() instanceof InstanceType.Spot
               ? base.withSpotMarketOptions(spotOptions(request.marketOptions()))
               : base;
    }

    private static Option<String> zoneOption(String zone) {
        return zone.isBlank()
               ? Option.empty()
               : Option.some(zone);
    }

    private static RunInstancesRequest.SpotMarketOptions spotOptions(MarketOptions marketOptions) {
        return switch (marketOptions) {
            case MarketOptions.Spot spot -> new RunInstancesRequest.SpotMarketOptions(spot.maxPrice(),
                                                                                      ec2InterruptionBehavior(spot.interruptionBehavior()));
            case MarketOptions.OnDemand ignored -> new RunInstancesRequest.SpotMarketOptions(Option.empty(), "terminate");
        };
    }

    private static String ec2InterruptionBehavior(MarketOptions.InterruptionBehavior behavior) {
        return switch (behavior) {
            case TERMINATE -> "terminate";
            case STOP -> "stop";
            case HIBERNATE -> "hibernate";
        };
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

    private static Promise<InstanceInfo> firstInstance(DescribeInstancesResponse response, InstanceId instanceId) {
        return response.allInstances()
                       .stream()
                       .findFirst()
                       .map(AwsComputeProvider::toInstanceInfo)
                       .map(Promise::success)
                       .orElseGet(() -> EnvironmentError.instanceNotFound(instanceId).promise());
    }

    private static List<InstanceInfo> toInstanceInfoList(DescribeInstancesResponse response) {
        return response.allInstances()
                       .stream()
                       .map(AwsComputeProvider::toInstanceInfo)
                       .toList();
    }

    static InstanceStatus mapStatus(String ec2Status) {
        return switch (ec2Status) {
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

        return Stream.concat(publicIp.stream(),
                             privateIp.stream())
                     .toList();
    }

    static Map<String, String> extractTags(Instance instance) {
        return option(instance.tagSet()).map(Instance.TagSet::items)
                     .map(AwsComputeProvider::tagsToMap)
                     .or(Map.of());
    }

    private static Map<String, String> tagsToMap(List<Instance.Tag> tags) {
        return tags.stream()
                   .collect(Collectors.toMap(Instance.Tag::key, Instance.Tag::value));
    }

    private static final String INSUFFICIENT_CAPACITY_CODE = "InsufficientInstanceCapacity";
    private static final String SPOT_MAX_PRICE_TOO_LOW_CODE = "SpotMaxPriceTooLow";

    /// Map EC2 provisioning failures to typed causes. Spot/on-demand capacity exhaustion
    /// (`InsufficientInstanceCapacity`) becomes the RETRYABLE [EnvironmentError.CapacityUnavailable]
    /// so the bootstrap/CTM zone rotation can advance (mirrors Hetzner's `resource_unavailable`
    /// handling); `SpotMaxPriceTooLow` is an operator config error → non-retryable
    /// [EnvironmentError.ProvisionFailed] with an actionable message.
    private static EnvironmentError toProvisionError(Cause cause) {
        return switch (cause) {
            case AwsError.ApiError api when INSUFFICIENT_CAPACITY_CODE.equals(api.code()) -> EnvironmentError.capacityUnavailable("",
                                                                                                                                  new RuntimeException(api.message()));
            case AwsError.ApiError api when SPOT_MAX_PRICE_TOO_LOW_CODE.equals(api.code()) -> EnvironmentError.provisionFailed(new RuntimeException("Spot max price is below the current market rate; raise max_price or omit it to accept the on-demand-capped rate. " + api.message()));
            default -> EnvironmentError.provisionFailed(new RuntimeException(cause.message()));
        };
    }

    private static EnvironmentError toTerminateError(InstanceId instanceId, Cause cause) {
        return EnvironmentError.terminateFailed(instanceId, new RuntimeException(cause.message()));
    }

    private static EnvironmentError toListInstancesError(Cause cause) {
        return EnvironmentError.listInstancesFailed(new RuntimeException(cause.message()));
    }
}
