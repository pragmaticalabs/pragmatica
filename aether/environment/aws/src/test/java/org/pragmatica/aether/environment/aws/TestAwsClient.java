package org.pragmatica.aether.environment.aws;

import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse.InstancesSet;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse.Reservation;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse.ReservationSet;
import org.pragmatica.cloud.aws.api.Instance;
import org.pragmatica.cloud.aws.api.RunInstancesResponse;
import org.pragmatica.cloud.aws.api.RunInstancesRequest;
import org.pragmatica.cloud.aws.api.TargetHealth;
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
    Queue<Promise<String>> secretResponses;

    List<String> lastTerminatedIds;
    List<String> lastRebootedIds;
    List<String> lastTagResourceIds;
    Map<String, String> lastTags;
    String lastDescribeTagKey;
    String lastDescribeTagValue;
    String lastRegisterTargetGroupArn;
    List<String> lastRegisteredIds;
    String lastDeregisterTargetGroupArn;
    List<String> lastDeregisteredIds;
    String lastTargetHealthArn;
    String lastSecretId;

    @Override
    public Promise<RunInstancesResponse> runInstances(RunInstancesRequest request) {
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
