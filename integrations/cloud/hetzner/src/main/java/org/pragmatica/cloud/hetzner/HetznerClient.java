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

package org.pragmatica.cloud.hetzner;

import org.pragmatica.cloud.hetzner.api.Firewall;
import org.pragmatica.cloud.hetzner.api.Firewall.ApplyToResourcesRequest;
import org.pragmatica.cloud.hetzner.api.Firewall.FirewallListResponse;
import org.pragmatica.cloud.hetzner.api.LoadBalancer;
import org.pragmatica.cloud.hetzner.api.LoadBalancer.CreateLoadBalancerRequest;
import org.pragmatica.cloud.hetzner.api.LoadBalancer.LoadBalancerListResponse;
import org.pragmatica.cloud.hetzner.api.LoadBalancer.LoadBalancerResponse;
import org.pragmatica.cloud.hetzner.api.LoadBalancer.TargetActionRequest;
import org.pragmatica.cloud.hetzner.api.Network;
import org.pragmatica.cloud.hetzner.api.Network.NetworkListResponse;
import org.pragmatica.cloud.hetzner.api.Network.NetworkResponse;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.cloud.hetzner.api.Server.ServerListResponse;
import org.pragmatica.cloud.hetzner.api.Server.ServerResponse;
import org.pragmatica.cloud.hetzner.api.SshKey;
import org.pragmatica.cloud.hetzner.api.SshKey.SshKeyListResponse;
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

/// Hetzner Cloud REST API client with Promise-based async operations.
public interface HetznerClient {

    /// Creates a new server.
    Promise<Server> createServer(CreateServerRequest request);

    /// Deletes a server by ID.
    Promise<Unit> deleteServer(long serverId);

    /// Gets a server by ID.
    Promise<Server> getServer(long serverId);

    /// Lists all servers.
    Promise<List<Server>> listServers();

    /// Lists all SSH keys.
    Promise<List<SshKey>> listSshKeys();

    /// Lists all networks.
    Promise<List<Network>> listNetworks();

    /// Gets a network by ID.
    Promise<Network> getNetwork(long networkId);

    /// Lists all firewalls.
    Promise<List<Firewall>> listFirewalls();

    /// Applies a firewall to a server.
    Promise<Unit> applyFirewall(long firewallId, long serverId);

    /// Creates a new load balancer.
    Promise<LoadBalancer> createLoadBalancer(CreateLoadBalancerRequest request);

    /// Deletes a load balancer by ID.
    Promise<Unit> deleteLoadBalancer(long loadBalancerId);

    /// Lists all load balancers.
    Promise<List<LoadBalancer>> listLoadBalancers();

    /// Adds a server target to a load balancer.
    Promise<Unit> addTarget(long loadBalancerId, long serverId);

    /// Removes a server target from a load balancer.
    Promise<Unit> removeTarget(long loadBalancerId, long serverId);

    /// Creates a HetznerClient with default HTTP operations.
    static HetznerClient hetznerClient(HetznerConfig config) {
        return hetznerClient(config, JdkHttpOperations.jdkHttpOperations());
    }

    /// Creates a HetznerClient with custom HTTP operations.
    static HetznerClient hetznerClient(HetznerConfig config, HttpOperations http) {
        return new HetznerClientRecord(config, http, JsonMapper.defaultJsonMapper());
    }

    /// Creates a HetznerClient with custom HTTP operations and JSON mapper.
    static HetznerClient hetznerClient(HetznerConfig config, HttpOperations http, JsonMapper mapper) {
        return new HetznerClientRecord(config, http, mapper);
    }
}

/// Implementation of HetznerClient using HttpOperations and JsonMapper.
record HetznerClientRecord(HetznerConfig config, HttpOperations http, JsonMapper mapper) implements HetznerClient {

    @Override
    public Promise<Server> createServer(CreateServerRequest request) {
        return postJson("/servers", request, ServerResponse.class)
            .map(ServerResponse::server);
    }

    @Override
    public Promise<Unit> deleteServer(long serverId) {
        return delete("/servers/" + serverId);
    }

    @Override
    public Promise<Server> getServer(long serverId) {
        return getJson("/servers/" + serverId, ServerResponse.class)
            .map(ServerResponse::server);
    }

    @Override
    public Promise<List<Server>> listServers() {
        return getJson("/servers", ServerListResponse.class)
            .map(ServerListResponse::servers);
    }

    @Override
    public Promise<List<SshKey>> listSshKeys() {
        return getJson("/ssh_keys", SshKeyListResponse.class)
            .map(SshKeyListResponse::sshKeys);
    }

    @Override
    public Promise<List<Network>> listNetworks() {
        return getJson("/networks", NetworkListResponse.class)
            .map(NetworkListResponse::networks);
    }

    @Override
    public Promise<Network> getNetwork(long networkId) {
        return getJson("/networks/" + networkId, NetworkResponse.class)
            .map(NetworkResponse::network);
    }

    @Override
    public Promise<List<Firewall>> listFirewalls() {
        return getJson("/firewalls", FirewallListResponse.class)
            .map(FirewallListResponse::firewalls);
    }

    @Override
    public Promise<Unit> applyFirewall(long firewallId, long serverId) {
        return postJsonDiscarding("/firewalls/" + firewallId + "/actions/apply_to_resources",
                                  ApplyToResourcesRequest.applyToServer(serverId));
    }

    @Override
    public Promise<LoadBalancer> createLoadBalancer(CreateLoadBalancerRequest request) {
        return postJson("/load_balancers", request, LoadBalancerResponse.class)
            .map(LoadBalancerResponse::loadBalancer);
    }

    @Override
    public Promise<Unit> deleteLoadBalancer(long loadBalancerId) {
        return delete("/load_balancers/" + loadBalancerId);
    }

    @Override
    public Promise<List<LoadBalancer>> listLoadBalancers() {
        return getJson("/load_balancers", LoadBalancerListResponse.class)
            .map(LoadBalancerListResponse::loadBalancers);
    }

    @Override
    public Promise<Unit> addTarget(long loadBalancerId, long serverId) {
        return postJsonDiscarding("/load_balancers/" + loadBalancerId + "/actions/add_target",
                                  TargetActionRequest.serverTarget(serverId));
    }

    @Override
    public Promise<Unit> removeTarget(long loadBalancerId, long serverId) {
        return postJsonDiscarding("/load_balancers/" + loadBalancerId + "/actions/remove_target",
                                  TargetActionRequest.serverTarget(serverId));
    }

    // --- Internal HTTP helpers ---

    private <T> Promise<T> getJson(String path, Class<T> responseType) {
        return http.sendString(buildGet(path))
                   .flatMap(result -> parseResponse(result, responseType));
    }

    private <T, R> Promise<R> postJson(String path, T body, Class<R> responseType) {
        return serializeBody(body)
            .flatMap(json -> http.sendString(buildPost(path, json)))
            .flatMap(result -> parseResponse(result, responseType));
    }

    private <T> Promise<Unit> postJsonDiscarding(String path, T body) {
        return serializeBody(body)
            .flatMap(json -> http.sendString(buildPost(path, json)))
            .flatMap(this::checkSuccess);
    }

    private Promise<Unit> delete(String path) {
        return http.sendString(buildDelete(path))
                   .flatMap(this::checkSuccess);
    }

    private <T> Promise<String> serializeBody(T body) {
        return mapper.writeAsString(body).async();
    }

    private <T> Promise<T> parseResponse(HttpResult<String> result, Class<T> responseType) {
        if (result.isSuccess()) {
            return mapper.readString(result.body(), responseType).async();
        }
        return HetznerError.fromResponse(result.statusCode(), result.body(), mapper).promise();
    }

    private Promise<Unit> checkSuccess(HttpResult<String> result) {
        if (result.isSuccess()) {
            return Promise.success(Unit.unit());
        }
        return HetznerError.fromResponse(result.statusCode(), result.body(), mapper).promise();
    }

    private HttpRequest buildGet(String path) {
        return authorizedRequest(path).GET().build();
    }

    private HttpRequest buildPost(String path, String jsonBody) {
        return authorizedRequest(path)
            .POST(BodyPublishers.ofString(jsonBody))
            .header("Content-Type", "application/json")
            .build();
    }

    private HttpRequest buildDelete(String path) {
        return authorizedRequest(path).DELETE().build();
    }

    private HttpRequest.Builder authorizedRequest(String path) {
        return HttpRequest.newBuilder()
                          .uri(URI.create(config.baseUrl() + path))
                          .header("Authorization", "Bearer " + config.apiToken());
    }
}
