// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.gcp;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ReadinessPolicy;
import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.AccessConfig;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.Disk;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.InitializeParams;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.Metadata;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.MetadataItem;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.NetworkInterfaceConfig;
import org.pragmatica.cloud.gcp.api.Instance;
import org.pragmatica.cloud.gcp.api.SetLabelsRequest;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public record GcpComputeProvider(GcpClient client, GcpEnvironmentConfig config) implements ComputeProvider {
    private static final Logger log = LoggerFactory.getLogger(GcpComputeProvider.class);
    private static final String MANAGED_LABEL_KEY = "aether-managed";
    private static final String MANAGED_LABEL_VALUE = "true";
    private static final String NODE_ID_LABEL = "aether-node-id";

    public static Result<GcpComputeProvider> gcpComputeProvider(GcpClient client, GcpEnvironmentConfig config) {
        return success(new GcpComputeProvider(client, config));
    }

    @Override
    public ProviderDefaults providerDefaults() {
        return ProviderDefaults.providerDefaults(config.machineType(),
                                                 config.sourceImage(),
                                                 "",
                                                 "",
                                                 option(config.userData()),
                                                 true);
    }

    /// Translate a fully-resolved [ProvisionRequest] into a GCP insert-instance call.
    /// [ProvisionRequest#resolve] has already applied the machine-type / image / zone / user-data
    /// precedence (#442, #459), so this consumes those fields verbatim — no provider-side
    /// re-derivation. The resolved instanceSize becomes the machine type and the resolved image
    /// becomes the boot-disk source image (both were previously DROPPED here in favor of the
    /// config defaults — this is the fix). A SPOT request is REJECTED loud: GCP's
    /// [InsertInstanceRequest] carries no provisioningModel field on this client, so honoring a
    /// spot request silently would provision an on-demand instance — the exact silent-downgrade
    /// this surface eliminates.
    @Override
    public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
        if (request.market() instanceof InstanceType.Spot) {
            return SPOT_UNSUPPORTED.promise();
        }

        var zone = zoneOverride(request.zone());
        var userData = request.userData().or("");
        var labels = labelsFor(request.context());

        return client.insertInstance(buildInsertRequest(request.instanceSize(),
                                                        request.image(),
                                                        zone,
                                                        userData,
                                                        labels))
                     .map(GcpComputeProvider::toInstanceInfo)
                     .flatMap(info -> confirmRunning(info,
                                                     ReadinessPolicy.cloudDefault()))
                     .onFailure(GcpComputeProvider::logProvisionFailureRollbackGap)
                     .mapError(GcpComputeProvider::toProvisionError);
    }

    /// GCP's [InsertInstanceRequest] exposes no `provisioningModel=SPOT` field on this client, so a
    /// spot arm cannot be assembled here. A SPOT request must fail loud rather than silently
    /// downgrade to an on-demand instance.
    private static final Cause SPOT_UNSUPPORTED = EnvironmentError.provisionFailed(new RuntimeException("GCP spot (provisioningModel=SPOT) provisioning is not yet implemented on this client; a SPOT request must not silently downgrade to on-demand"));

    /// A blank resolved zone requests the provider's client-level default placement; a concrete
    /// zone is threaded onto the insert request as the per-instance override.
    private static Option<String> zoneOverride(String zone) {
        return zone.isBlank()
               ? Option.empty()
               : Option.some(zone);
    }

    /// Rollback acknowledgment for GCP provisions. GCP's `insertInstance` is a single
    /// atomic operation: either it returns success with an Instance, or it returns
    /// failure with no resource created. There is no separate "partial" state the
    /// provider can observe at create-time — but post-create readiness confirmation
    /// (confirmRunning, infra-readiness only) can now surface a VM that never reached
    /// RUNNING. That orphan IS a real resource; cleanup is owned at a higher layer
    /// (CTM auto-heal terminate), so surface a WARN here so operators can correlate.
    private static void logProvisionFailureRollbackGap(Cause cause) {
        log.warn("GCP provision failed ({}); no instance-side rollback issued because insertInstance is atomic — relying on caller to retry or sweep",
                 cause.message());
    }

    @Override
    public Promise<Unit> terminate(InstanceId instanceId) {
        return client.deleteInstance(instanceId.value())
                     .mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances() {
        return client.listInstances()
                     .map(GcpComputeProvider::toInstanceInfoList)
                     .mapError(GcpComputeProvider::toListInstancesError);
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return client.listInstances(toLabelFilter(tagFilter))
                     .map(GcpComputeProvider::toInstanceInfoList)
                     .mapError(GcpComputeProvider::toListInstancesError);
    }

    @Override
    public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        return client.getInstance(instanceId.value())
                     .map(GcpComputeProvider::toInstanceInfo)
                     .mapError(GcpComputeProvider::toProvisionError);
    }

    @Override
    public Promise<Unit> restart(InstanceId id) {
        return client.resetInstance(id.value())
                     .mapToUnit();
    }

    @Override
    public Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return client.getInstance(id.value())
                     .flatMap(instance -> setLabelsOnInstance(id.value(),
                                                              instance,
                                                              tags));
    }

    private Promise<Unit> setLabelsOnInstance(String instanceName, Instance instance, Map<String, String> tags) {
        var fingerprint = extractLabelFingerprint(instance);

        return client.setLabels(instanceName,
                                new SetLabelsRequest(tags, fingerprint))
                     .mapToUnit();
    }

    private static String extractLabelFingerprint(Instance instance) {
        return "";
    }

    private InsertInstanceRequest buildInsertRequest(String machineType,
                                                     String image,
                                                     Option<String> zoneOverride,
                                                     String userData,
                                                     Map<String, String> labels) {
        var name = generateInstanceName();
        var disk = buildBootDisk(image);
        var networkInterface = buildNetworkInterface();
        var metadata = buildMetadata(userData);

        return new InsertInstanceRequest(name,
                                         machineType,
                                         List.of(disk),
                                         List.of(networkInterface),
                                         labels,
                                         metadata,
                                         zoneOverride);
    }

    private static Map<String, String> labelsFor(ProvisionContext ctx) {
        var labels = new java.util.HashMap<String, String>();

        labels.put(MANAGED_LABEL_KEY, MANAGED_LABEL_VALUE);
        var clusterName = resolveClusterName(ctx);

        if (!clusterName.isEmpty()) {
            labels.put("aether-cluster", clusterName);
        }

        if (!ctx.role().isEmpty()) {
            labels.put("aether-role", ctx.role());
        }

        if (!ctx.sourceName().isEmpty()) {
            labels.put("aether-source", ctx.sourceName());
        }

        labels.put(NODE_ID_LABEL, ctx.resolveNodeId());
        labels.putAll(ctx.extraTags());

        return Map.copyOf(labels);
    }

    private static String resolveClusterName(ProvisionContext ctx) {
        if (!ctx.clusterName().isEmpty()) {
            return ctx.clusterName();
        }

        var fromEnv = System.getenv("AETHER_CLUSTER_NAME");

        return fromEnv != null && !fromEnv.isEmpty()
               ? fromEnv
               : "";
    }

    private Disk buildBootDisk(String image) {
        return new Disk(true, true, new InitializeParams(image, 20, "pd-standard"));
    }

    private NetworkInterfaceConfig buildNetworkInterface() {
        return new NetworkInterfaceConfig(config.network(),
                                          config.subnetwork(),
                                          List.of(new AccessConfig("External NAT", "ONE_TO_ONE_NAT")));
    }

    private Metadata buildMetadata(String userData) {
        return new Metadata(List.of(new MetadataItem("startup-script", userData)));
    }

    private static String generateInstanceName() {
        return "aether-" + UUID.randomUUID()
                               .toString()
                               .substring(0, 8);
    }

    static InstanceInfo toInstanceInfo(Instance instance) {
        var labels = safeLabels(instance);

        return new InstanceInfo(new InstanceId(instance.name()),
                                mapStatus(instance.status()),
                                collectAddresses(instance),
                                InstanceType.ON_DEMAND,
                                labels,
                                Option.option(labels.get(NODE_ID_LABEL)));
    }

    private static Map<String, String> safeLabels(Instance instance) {
        return option(instance.labels()).or(Map.of());
    }

    private static List<InstanceInfo> toInstanceInfoList(List<Instance> instances) {
        return instances.stream()
                        .map(GcpComputeProvider::toInstanceInfo)
                        .toList();
    }

    static InstanceStatus mapStatus(String gcpStatus) {
        return switch (gcpStatus) {
            case "PROVISIONING", "STAGING" -> InstanceStatus.PROVISIONING;
            case "RUNNING" -> InstanceStatus.RUNNING;
            case "STOPPING", "TERMINATED", "SUSPENDED", "SUSPENDING" -> InstanceStatus.STOPPING;
            default -> InstanceStatus.TERMINATED;
        };
    }

    static List<String> collectAddresses(Instance instance) {
        return option(instance.networkInterfaces()).map(GcpComputeProvider::extractIpsFromInterfaces)
                     .or(List.of());
    }

    private static List<String> extractIpsFromInterfaces(List<Instance.NetworkInterface> interfaces) {
        return interfaces.stream()
                         .map(Instance.NetworkInterface::networkIP)
                         .toList();
    }

    static String toLabelFilter(Map<String, String> tagFilter) {
        return tagFilter.entrySet()
                        .stream()
                        .map(GcpComputeProvider::toLabelFilterEntry)
                        .reduce(GcpComputeProvider::combineWithAnd)
                        .orElse("");
    }

    private static String toLabelFilterEntry(Map.Entry<String, String> entry) {
        return "labels." + entry.getKey() + "=" + entry.getValue();
    }

    private static String combineWithAnd(String a, String b) {
        return a + " AND " + b;
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
