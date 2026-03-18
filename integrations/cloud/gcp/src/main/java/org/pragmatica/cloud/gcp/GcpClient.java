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

package org.pragmatica.cloud.gcp;

import org.pragmatica.cloud.gcp.api.InsertInstanceRequest;
import org.pragmatica.cloud.gcp.api.Instance;
import org.pragmatica.cloud.gcp.api.Instance.InstanceListResponse;
import org.pragmatica.cloud.gcp.api.NetworkEndpoint;
import org.pragmatica.cloud.gcp.api.NetworkEndpoint.NetworkEndpointListResponse;
import org.pragmatica.cloud.gcp.api.NetworkEndpoint.NetworkEndpointRequest;
import org.pragmatica.cloud.gcp.api.NetworkEndpoint.NetworkEndpointWithHealth;
import org.pragmatica.cloud.gcp.api.Operation;
import org.pragmatica.cloud.gcp.api.SecretPayload;
import org.pragmatica.cloud.gcp.api.SetLabelsRequest;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/// GCP Cloud REST API client with Promise-based async operations.
public interface GcpClient {
    /// Inserts a new Compute Engine instance.
    Promise<Instance> insertInstance(InsertInstanceRequest request);

    /// Deletes a Compute Engine instance by name.
    Promise<Unit> deleteInstance(String instanceName);

    /// Gets a Compute Engine instance by name.
    Promise<Instance> getInstance(String instanceName);

    /// Lists all Compute Engine instances in the configured zone.
    Promise<List<Instance>> listInstances();

    /// Lists Compute Engine instances matching a label filter.
    Promise<List<Instance>> listInstances(String labelFilter);

    /// Resets (hard reboot) a Compute Engine instance.
    Promise<Operation> resetInstance(String instanceName);

    /// Sets labels on a Compute Engine instance.
    Promise<Operation> setLabels(String instanceName, SetLabelsRequest request);

    /// Attaches a network endpoint to a network endpoint group.
    Promise<Operation> attachNetworkEndpoint(String negName, NetworkEndpoint endpoint);

    /// Detaches a network endpoint from a network endpoint group.
    Promise<Operation> detachNetworkEndpoint(String negName, NetworkEndpoint endpoint);

    /// Lists network endpoints in a network endpoint group.
    Promise<List<NetworkEndpoint>> listNetworkEndpoints(String negName);

    /// Accesses the latest version of a secret from Secret Manager.
    Promise<String> accessSecretVersion(String secretName);

    /// Creates a GcpClient with default HTTP operations.
    static GcpClient gcpClient(GcpConfig config) {
        return gcpClient(config, JdkHttpOperations.jdkHttpOperations());
    }

    /// Creates a GcpClient with custom HTTP operations.
    static GcpClient gcpClient(GcpConfig config, HttpOperations http) {
        return new GcpClientRecord(config, http, JsonMapper.defaultJsonMapper(), GcpTokenManager.gcpTokenManager(config));
    }

    /// Creates a GcpClient with custom HTTP operations and JSON mapper.
    static GcpClient gcpClient(GcpConfig config, HttpOperations http, JsonMapper mapper) {
        return new GcpClientRecord(config, http, mapper, GcpTokenManager.gcpTokenManager(config));
    }
}

/// Implementation of GcpClient using HttpOperations and JsonMapper.
record GcpClientRecord(GcpConfig config, HttpOperations http, JsonMapper mapper, GcpTokenManager tokenManager) implements GcpClient {
    private static final String SECRET_MANAGER_BASE_URL = "https://secretmanager.googleapis.com/v1";

    @Override
    public Promise<Instance> insertInstance(InsertInstanceRequest request) {
        return postJson(zonePath("/instances"), request, Instance.class);
    }

    @Override
    public Promise<Unit> deleteInstance(String instanceName) {
        return delete(zonePath("/instances/" + instanceName));
    }

    @Override
    public Promise<Instance> getInstance(String instanceName) {
        return getJson(zonePath("/instances/" + instanceName), Instance.class);
    }

    @Override
    public Promise<List<Instance>> listInstances() {
        return getJson(zonePath("/instances"), InstanceListResponse.class)
            .map(GcpClientRecord::extractInstances);
    }

    @Override
    public Promise<List<Instance>> listInstances(String labelFilter) {
        return getJson(zonePath("/instances") + "?filter=" + encodeQueryParam(labelFilter), InstanceListResponse.class)
            .map(GcpClientRecord::extractInstances);
    }

    @Override
    public Promise<Operation> resetInstance(String instanceName) {
        return postEmpty(zonePath("/instances/" + instanceName + "/reset"), Operation.class);
    }

    @Override
    public Promise<Operation> setLabels(String instanceName, SetLabelsRequest request) {
        return postJson(zonePath("/instances/" + instanceName + "/setLabels"), request, Operation.class);
    }

    @Override
    public Promise<Operation> attachNetworkEndpoint(String negName, NetworkEndpoint endpoint) {
        var body = new NetworkEndpointRequest(List.of(endpoint));
        return postJson(zonePath("/networkEndpointGroups/" + negName + "/attachNetworkEndpoints"), body, Operation.class);
    }

    @Override
    public Promise<Operation> detachNetworkEndpoint(String negName, NetworkEndpoint endpoint) {
        var body = new NetworkEndpointRequest(List.of(endpoint));
        return postJson(zonePath("/networkEndpointGroups/" + negName + "/detachNetworkEndpoints"), body, Operation.class);
    }

    @Override
    public Promise<List<NetworkEndpoint>> listNetworkEndpoints(String negName) {
        return postEmpty(zonePath("/networkEndpointGroups/" + negName + "/listNetworkEndpoints"), NetworkEndpointListResponse.class)
            .map(GcpClientRecord::extractEndpoints);
    }

    @Override
    public Promise<String> accessSecretVersion(String secretName) {
        var path = "/projects/" + config.projectId() + "/secrets/" + secretName + "/versions/latest:access";
        return getJsonAbsolute(SECRET_MANAGER_BASE_URL + path, SecretPayload.class)
            .map(GcpClientRecord::decodeSecretPayload);
    }

    // --- Internal HTTP helpers ---

    private <T> Promise<T> getJson(String path, Class<T> responseType) {
        return tokenManager.getAccessToken(http, mapper)
                           .flatMap(token -> http.sendString(buildGet(config.baseUrl() + path, token)))
                           .flatMap(result -> parseResponse(result, responseType));
    }

    private <T> Promise<T> getJsonAbsolute(String url, Class<T> responseType) {
        return tokenManager.getAccessToken(http, mapper)
                           .flatMap(token -> http.sendString(buildGet(url, token)))
                           .flatMap(result -> parseResponse(result, responseType));
    }

    private <T, R> Promise<R> postJson(String path, T body, Class<R> responseType) {
        return tokenManager.getAccessToken(http, mapper)
                           .flatMap(token -> serializeAndPost(path, body, token))
                           .flatMap(result -> parseResponse(result, responseType));
    }

    private <R> Promise<R> postEmpty(String path, Class<R> responseType) {
        return tokenManager.getAccessToken(http, mapper)
                           .flatMap(token -> http.sendString(buildPost(config.baseUrl() + path, "", token)))
                           .flatMap(result -> parseResponse(result, responseType));
    }

    private Promise<Unit> delete(String path) {
        return tokenManager.getAccessToken(http, mapper)
                           .flatMap(token -> http.sendString(buildDelete(config.baseUrl() + path, token)))
                           .flatMap(this::checkSuccess);
    }

    private <T> Promise<HttpResult<String>> serializeAndPost(String path, T body, String token) {
        return mapper.writeAsString(body)
                     .async()
                     .flatMap(json -> http.sendString(buildPost(config.baseUrl() + path, json, token)));
    }

    private <T> Promise<T> parseResponse(HttpResult<String> result, Class<T> responseType) {
        if (result.isSuccess()) {
            return mapper.readString(result.body(), responseType).async();
        }
        return GcpError.fromResponse(result.statusCode(), result.body(), mapper).promise();
    }

    private Promise<Unit> checkSuccess(HttpResult<String> result) {
        if (result.isSuccess()) {
            return Promise.success(Unit.unit());
        }
        return GcpError.fromResponse(result.statusCode(), result.body(), mapper).promise();
    }

    private String zonePath(String suffix) {
        return "/projects/" + config.projectId() + "/zones/" + config.zone() + suffix;
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

    private static HttpRequest buildDelete(String url, String token) {
        return authorizedRequest(url, token).DELETE().build();
    }

    private static HttpRequest.Builder authorizedRequest(String url, String token) {
        return HttpRequest.newBuilder()
                          .uri(URI.create(url))
                          .header("Authorization", "Bearer " + token);
    }

    private static List<Instance> extractInstances(InstanceListResponse response) {
        return Option.option(response.items()).or(List.of());
    }

    private static List<NetworkEndpoint> extractEndpoints(NetworkEndpointListResponse response) {
        return Option.option(response.items())
                     .map(items -> items.stream().map(NetworkEndpointWithHealth::networkEndpoint).toList())
                     .or(List.of());
    }

    private static String decodeSecretPayload(SecretPayload payload) {
        return new String(Base64.getDecoder().decode(payload.payload().data()), StandardCharsets.UTF_8);
    }

    private static String encodeQueryParam(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
