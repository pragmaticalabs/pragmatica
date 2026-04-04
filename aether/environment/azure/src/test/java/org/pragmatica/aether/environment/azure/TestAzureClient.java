package org.pragmatica.aether.environment.azure;

import org.pragmatica.cloud.azure.AzureClient;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer.BackendPool;
import org.pragmatica.cloud.azure.api.CreateVmRequest;
import org.pragmatica.cloud.azure.api.ResourceRow;
import org.pragmatica.cloud.azure.api.VirtualMachine;
import org.pragmatica.cloud.azure.api.VirtualMachine.VmProperties;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.Queue;

/// Test stub for AzureClient that returns canned responses and captures arguments.
final class TestAzureClient implements AzureClient {
    Promise<VirtualMachine> createVmResponse = Promise.success(
        new VirtualMachine("id", "default", "eastus", Map.of(), new VmProperties("vmid", "Succeeded", null)));
    Promise<Unit> deleteVmResponse = Promise.success(Unit.unit());
    Promise<VirtualMachine> getVmResponse = Promise.success(
        new VirtualMachine("id", "default", "eastus", Map.of(), new VmProperties("vmid", "Succeeded", null)));
    Promise<List<VirtualMachine>> listVmsResponse = Promise.success(List.of());
    Promise<Unit> restartVmResponse = Promise.success(Unit.unit());
    Promise<VirtualMachine> updateTagsResponse = Promise.success(
        new VirtualMachine("id", "default", "eastus", Map.of(), new VmProperties("vmid", "Succeeded", null)));
    Promise<AzureLoadBalancer> getLbResponse = Promise.success(
        new AzureLoadBalancer("lb-id", "test-lb", new AzureLoadBalancer.LbProperties(List.of())));
    Promise<AzureLoadBalancer> updateLbBackendPoolResponse = Promise.success(
        new AzureLoadBalancer("lb-id", "test-lb", new AzureLoadBalancer.LbProperties(List.of())));
    Promise<List<ResourceRow>> queryResourcesResponse = Promise.success(List.of());
    Promise<String> getSecretResponse = Promise.success("secret-value");
    Queue<Promise<String>> secretResponses;

    String lastDeletedVmName;
    String lastGetVmName;
    String lastRestartedVmName;
    String lastUpdateTagsVmName;
    Map<String, String> lastUpdateTags;
    String lastQueryResourcesKql;
    String lastGetSecretVaultName;
    String lastGetSecretName;
    String lastGetLbName;
    String lastUpdateLbName;
    String lastUpdateLbPoolName;
    BackendPool lastUpdateLbPool;

    @Override
    public Promise<VirtualMachine> createVm(CreateVmRequest request) {
        return createVmResponse;
    }

    @Override
    public Promise<Unit> deleteVm(String vmName) {
        lastDeletedVmName = vmName;
        return deleteVmResponse;
    }

    @Override
    public Promise<VirtualMachine> getVm(String vmName) {
        lastGetVmName = vmName;
        return getVmResponse;
    }

    @Override
    public Promise<List<VirtualMachine>> listVms() {
        return listVmsResponse;
    }

    @Override
    public Promise<Unit> restartVm(String vmName) {
        lastRestartedVmName = vmName;
        return restartVmResponse;
    }

    @Override
    public Promise<VirtualMachine> updateTags(String vmName, Map<String, String> tags) {
        lastUpdateTagsVmName = vmName;
        lastUpdateTags = tags;
        return updateTagsResponse;
    }

    @Override
    public Promise<AzureLoadBalancer> getLb(String lbName) {
        lastGetLbName = lbName;
        return getLbResponse;
    }

    @Override
    public Promise<AzureLoadBalancer> updateLbBackendPool(String lbName, String poolName, BackendPool pool) {
        lastUpdateLbName = lbName;
        lastUpdateLbPoolName = poolName;
        lastUpdateLbPool = pool;
        return updateLbBackendPoolResponse;
    }

    @Override
    public Promise<List<ResourceRow>> queryResources(String kqlQuery) {
        lastQueryResourcesKql = kqlQuery;
        return queryResourcesResponse;
    }

    @Override
    public Promise<String> getSecret(String vaultName, String secretName) {
        lastGetSecretVaultName = vaultName;
        lastGetSecretName = secretName;
        if (secretResponses != null && !secretResponses.isEmpty()) {
            return secretResponses.poll();
        }
        return getSecretResponse;
    }
}
