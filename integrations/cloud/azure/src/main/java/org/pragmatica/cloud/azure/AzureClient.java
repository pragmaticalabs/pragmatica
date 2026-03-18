/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.cloud.azure;

import org.pragmatica.cloud.azure.api.AzureLoadBalancer;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer.BackendPool;
import org.pragmatica.cloud.azure.api.CreateVmRequest;
import org.pragmatica.cloud.azure.api.ResourceGraphRequest;
import org.pragmatica.cloud.azure.api.ResourceGraphResponse;
import org.pragmatica.cloud.azure.api.ResourceRow;
import org.pragmatica.cloud.azure.api.SecretResponse;
import org.pragmatica.cloud.azure.api.VirtualMachine;
import org.pragmatica.cloud.azure.api.VirtualMachine.VmListResponse;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.List;
import java.util.Map;

/// Azure Cloud REST API client with Promise-based async operations.
public interface AzureClient {
    /// Creates a virtual machine.
    Promise<VirtualMachine> createVm(CreateVmRequest request);

    /// Deletes a virtual machine by name.
    Promise<Unit> deleteVm(String vmName);

    /// Gets a virtual machine by name.
    Promise<VirtualMachine> getVm(String vmName);

    /// Lists all virtual machines in the resource group.
    Promise<List<VirtualMachine>> listVms();

    /// Restarts a virtual machine by name.
    Promise<Unit> restartVm(String vmName);

    /// Updates tags on a virtual machine.
    Promise<VirtualMachine> updateTags(String vmName, Map<String, String> tags);

    /// Gets a load balancer by name.
    Promise<AzureLoadBalancer> getLb(String lbName);

    /// Updates a backend address pool on a load balancer.
    Promise<AzureLoadBalancer> updateLbBackendPool(String lbName, String poolName, BackendPool pool);

    /// Queries resources using Azure Resource Graph (KQL).
    Promise<List<ResourceRow>> queryResources(String kqlQuery);

    /// Gets a secret value from Azure Key Vault.
    Promise<String> getSecret(String vaultName, String secretName);

    /// Creates an AzureClient with default HTTP operations.
    static AzureClient azureClient(AzureConfig config) {
        return azureClient(config, JdkHttpOperations.jdkHttpOperations());
    }

    /// Creates an AzureClient with custom HTTP operations.
    static AzureClient azureClient(AzureConfig config, HttpOperations http) {
        return new AzureClientRecord(config, http, JsonMapper.defaultJsonMapper(), AzureTokenManager.azureTokenManager(config));
    }

    /// Creates an AzureClient with custom HTTP operations and JSON mapper.
    static AzureClient azureClient(AzureConfig config, HttpOperations http, JsonMapper mapper) {
        return new AzureClientRecord(config, http, mapper, AzureTokenManager.azureTokenManager(config));
    }

    /// Creates an AzureClient with custom HTTP operations, JSON mapper, and token manager.
    static AzureClient azureClient(AzureConfig config, HttpOperations http, JsonMapper mapper, AzureTokenManager tokenManager) {
        return new AzureClientRecord(config, http, mapper, tokenManager);
    }
}

/// Implementation of AzureClient using HttpOperations and JsonMapper.
record AzureClientRecord(AzureConfig config, HttpOperations http, JsonMapper mapper,
                          AzureTokenManager tokenManager) implements AzureClient {
    private static final String COMPUTE_API_VERSION = "2024-07-01";
    private static final String NETWORK_API_VERSION = "2024-03-01";
    private static final String RESOURCE_GRAPH_API_VERSION = "2021-03-01";
    private static final String KEY_VAULT_API_VERSION = "7.4";

    @Override
    public Promise<VirtualMachine> createVm(CreateVmRequest request) {
        return tokenManager.getManagementToken(http, mapper)
                           .flatMap(token -> putJson(computeVmPath(request.name()), request, VirtualMachine.class, token));
    }

    @Override
    public Promise<Unit> deleteVm(String vmName) {
        return tokenManager.getManagementToken(http, mapper)
                           .flatMap(token -> delete(computeVmPath(vmName), token));
    }

    @Override
    public Promise<VirtualMachine> getVm(String vmName) {
        return tokenManager.getManagementToken(http, mapper)
                           .flatMap(token -> getJson(computeVmPath(vmName), VirtualMachine.class, token));
    }

    @Override
    public Promise<List<VirtualMachine>> listVms() {
        var path = config.resourcePrefix() + "/Microsoft.Compute/virtualMachines?api-version=" + COMPUTE_API_VERSION;
        return tokenManager.getManagementToken(http, mapper)
                           .flatMap(token -> getJson(path, VmListResponse.class, token))
                           .map(VmListResponse::value);
    }

    @Override
    public Promise<Unit> restartVm(String vmName) {
        var path = config.resourcePrefix() + "/Microsoft.Compute/virtualMachines/" + vmName
                   + "/restart?api-version=" + COMPUTE_API_VERSION;
        return tokenManager.getManagementToken(http, mapper)
                           .flatMap(token -> postEmpty(path, token));
    }

    @Override
    public Promise<VirtualMachine> updateTags(String vmName, Map<String, String> tags) {
        return tokenManager.getManagementToken(http, mapper)
                           .flatMap(token -> patchJson(computeVmPath(vmName), Map.of("tags", tags), VirtualMachine.class, token));
    }

    @Override
    public Promise<AzureLoadBalancer> getLb(String lbName) {
        var path = config.resourcePrefix() + "/Microsoft.Network/loadBalancers/" + lbName
                   + "?api-version=" + NETWORK_API_VERSION;
        return tokenManager.getManagementToken(http, mapper)
                           .flatMap(token -> getJson(path, AzureLoadBalancer.class, token));
    }

    @Override
    public Promise<AzureLoadBalancer> updateLbBackendPool(String lbName, String poolName, BackendPool pool) {
        var path = config.resourcePrefix() + "/Microsoft.Network/loadBalancers/" + lbName
                   + "/backendAddressPools/" + poolName + "?api-version=" + NETWORK_API_VERSION;
        return tokenManager.getManagementToken(http, mapper)
                           .flatMap(token -> putJson(path, pool, AzureLoadBalancer.class, token));
    }

    @Override
    public Promise<List<ResourceRow>> queryResources(String kqlQuery) {
        var request = ResourceGraphRequest.resourceGraphRequest(List.of(config.subscriptionId()), kqlQuery);
        return tokenManager.getManagementToken(http, mapper)
                           .flatMap(token -> postJson(
                               "/providers/Microsoft.ResourceGraph/resources?api-version=" + RESOURCE_GRAPH_API_VERSION,
                               request, ResourceGraphResponse.class, token))
                           .map(ResourceGraphResponse::data);
    }

    @Override
    public Promise<String> getSecret(String vaultName, String secretName) {
        var url = "https://" + vaultName + ".vault.azure.net/secrets/" + secretName
                  + "?api-version=" + KEY_VAULT_API_VERSION;
        return tokenManager.getVaultToken(http, mapper)
                           .flatMap(token -> getJsonAbsolute(url, SecretResponse.class, token))
                           .map(SecretResponse::value);
    }

    // --- Internal HTTP helpers ---

    private String computeVmPath(String vmName) {
        return config.resourcePrefix() + "/Microsoft.Compute/virtualMachines/" + vmName
               + "?api-version=" + COMPUTE_API_VERSION;
    }

    private <T> Promise<T> getJson(String path, Class<T> responseType, String token) {
        return http.sendString(buildGet(config.baseUrl() + path, token))
                   .flatMap(result -> parseResponse(result, responseType));
    }

    private <T> Promise<T> getJsonAbsolute(String url, Class<T> responseType, String token) {
        return http.sendString(buildGet(url, token))
                   .flatMap(result -> parseResponse(result, responseType));
    }

    private <T, R> Promise<R> postJson(String path, T body, Class<R> responseType, String token) {
        return serializeBody(body)
            .flatMap(json -> http.sendString(buildPost(config.baseUrl() + path, json, token)))
            .flatMap(result -> parseResponse(result, responseType));
    }

    private Promise<Unit> postEmpty(String path, String token) {
        return http.sendString(buildPost(config.baseUrl() + path, "", token))
                   .flatMap(this::checkSuccess);
    }

    private <T, R> Promise<R> putJson(String path, T body, Class<R> responseType, String token) {
        return serializeBody(body)
            .flatMap(json -> http.sendString(buildPut(config.baseUrl() + path, json, token)))
            .flatMap(result -> parseResponse(result, responseType));
    }

    private <T, R> Promise<R> patchJson(String path, T body, Class<R> responseType, String token) {
        return serializeBody(body)
            .flatMap(json -> http.sendString(buildPatch(config.baseUrl() + path, json, token)))
            .flatMap(result -> parseResponse(result, responseType));
    }

    private Promise<Unit> delete(String path, String token) {
        return http.sendString(buildDelete(config.baseUrl() + path, token))
                   .flatMap(this::checkSuccess);
    }

    private <T> Promise<String> serializeBody(T body) {
        return mapper.writeAsString(body).async();
    }

    private <T> Promise<T> parseResponse(HttpResult<String> result, Class<T> responseType) {
        if (result.isSuccess()) {
            return mapper.readString(result.body(), responseType).async();
        }
        return AzureError.fromResponse(result.statusCode(), result.body(), mapper).promise();
    }

    private Promise<Unit> checkSuccess(HttpResult<String> result) {
        if (result.isSuccess()) {
            return Promise.success(Unit.unit());
        }
        return AzureError.fromResponse(result.statusCode(), result.body(), mapper).promise();
    }

    private static HttpRequest buildGet(String url, String token) {
        return authorizedRequest(url, token).GET().build();
    }

    private static HttpRequest buildPost(String url, String jsonBody, String token) {
        return authorizedRequest(url, token)
            .POST(BodyPublishers.ofString(jsonBody))
            .header("Content-Type", "application/json")
            .build();
    }

    private static HttpRequest buildPut(String url, String jsonBody, String token) {
        return authorizedRequest(url, token)
            .PUT(BodyPublishers.ofString(jsonBody))
            .header("Content-Type", "application/json")
            .build();
    }

    private static HttpRequest buildPatch(String url, String jsonBody, String token) {
        return authorizedRequest(url, token)
            .method("PATCH", BodyPublishers.ofString(jsonBody))
            .header("Content-Type", "application/json")
            .build();
    }

    private static HttpRequest buildDelete(String url, String token) {
        return authorizedRequest(url, token).DELETE().build();
    }

    private static HttpRequest.Builder authorizedRequest(String url, String token) {
        return HttpRequest.newBuilder()
                          .uri(URI.create(url))
                          .header("Authorization", "Bearer " + token);
    }
}
