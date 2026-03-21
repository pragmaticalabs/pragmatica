package org.pragmatica.aether.environment.gcp;

import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest;
import org.pragmatica.cloud.gcp.api.Instance;
import org.pragmatica.cloud.gcp.api.NetworkEndpoint;
import org.pragmatica.cloud.gcp.api.Operation;
import org.pragmatica.cloud.gcp.api.SetLabelsRequest;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

/// Test stub for GcpClient that returns canned responses and captures arguments.
final class TestGcpClient implements GcpClient {
    private static final Operation OK_OPERATION = new Operation("op-1", "DONE", "", "");

    Promise<Instance> insertInstanceResponse = Promise.success(GcpComputeProviderTest.runningInstance("default"));
    Promise<Unit> deleteInstanceResponse = Promise.success(Unit.unit());
    Promise<Instance> getInstanceResponse = Promise.success(GcpComputeProviderTest.runningInstance("default"));
    Promise<List<Instance>> listInstancesResponse = Promise.success(List.of());
    Promise<Operation> resetInstanceResponse = Promise.success(OK_OPERATION);
    Promise<Operation> setLabelsResponse = Promise.success(OK_OPERATION);
    Promise<Operation> attachEndpointResponse = Promise.success(OK_OPERATION);
    Promise<Operation> detachEndpointResponse = Promise.success(OK_OPERATION);
    Promise<List<NetworkEndpoint>> listEndpointsResponse = Promise.success(List.of());
    Promise<String> accessSecretResponse = Promise.success("test-secret");

    String lastDeletedInstanceName;
    String lastGetInstanceName;
    String lastResetInstanceName;
    String lastSetLabelsInstanceName;
    Map<String, String> lastSetLabels;
    String lastLabelFilter;
    String lastSecretName;
    String lastAttachNegName;
    NetworkEndpoint lastAttachEndpoint;
    String lastDetachNegName;
    NetworkEndpoint lastDetachEndpoint;
    String lastListEndpointsNegName;

    @Override
    public Promise<Instance> insertInstance(InsertInstanceRequest request) {
        return insertInstanceResponse;
    }

    @Override
    public Promise<Unit> deleteInstance(String instanceName) {
        lastDeletedInstanceName = instanceName;
        return deleteInstanceResponse;
    }

    @Override
    public Promise<Instance> getInstance(String instanceName) {
        lastGetInstanceName = instanceName;
        return getInstanceResponse;
    }

    @Override
    public Promise<List<Instance>> listInstances() {
        return listInstancesResponse;
    }

    @Override
    public Promise<List<Instance>> listInstances(String labelFilter) {
        lastLabelFilter = labelFilter;
        return listInstancesResponse;
    }

    @Override
    public Promise<Operation> resetInstance(String instanceName) {
        lastResetInstanceName = instanceName;
        return resetInstanceResponse;
    }

    @Override
    public Promise<Operation> setLabels(String instanceName, SetLabelsRequest request) {
        lastSetLabelsInstanceName = instanceName;
        lastSetLabels = request.labels();
        return setLabelsResponse;
    }

    @Override
    public Promise<Operation> attachNetworkEndpoint(String negName, NetworkEndpoint endpoint) {
        lastAttachNegName = negName;
        lastAttachEndpoint = endpoint;
        return attachEndpointResponse;
    }

    @Override
    public Promise<Operation> detachNetworkEndpoint(String negName, NetworkEndpoint endpoint) {
        lastDetachNegName = negName;
        lastDetachEndpoint = endpoint;
        return detachEndpointResponse;
    }

    @Override
    public Promise<List<NetworkEndpoint>> listNetworkEndpoints(String negName) {
        lastListEndpointsNegName = negName;
        return listEndpointsResponse;
    }

    @Override
    public Promise<String> accessSecretVersion(String secretName) {
        lastSecretName = secretName;
        return accessSecretResponse;
    }
}
