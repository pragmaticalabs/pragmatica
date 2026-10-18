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

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.cloud.hetzner.api.Firewall;
import org.pragmatica.cloud.hetzner.api.Firewall.ApplyToResourcesRequest;
import org.pragmatica.cloud.hetzner.api.Firewall.CreateFirewallRequest;
import org.pragmatica.cloud.hetzner.api.Firewall.FirewallListResponse;
import org.pragmatica.cloud.hetzner.api.Firewall.FirewallResponse;
import org.pragmatica.cloud.hetzner.api.Firewall.RemoveFromResourcesRequest;
import org.pragmatica.cloud.hetzner.api.Firewall.SetRulesRequest;
import org.pragmatica.cloud.hetzner.api.FloatingIp;
import org.pragmatica.cloud.hetzner.api.FloatingIp.AssignRequest;
import org.pragmatica.cloud.hetzner.api.FloatingIp.FloatingIpListResponse;
import org.pragmatica.cloud.hetzner.api.FloatingIp.FloatingIpResponse;
import org.pragmatica.cloud.hetzner.api.LoadBalancer;
import org.pragmatica.cloud.hetzner.api.LoadBalancer.CreateLoadBalancerRequest;
import org.pragmatica.cloud.hetzner.api.LoadBalancer.LoadBalancerListResponse;
import org.pragmatica.cloud.hetzner.api.LoadBalancer.LoadBalancerResponse;
import org.pragmatica.cloud.hetzner.api.LoadBalancer.IpTargetActionRequest;
import org.pragmatica.cloud.hetzner.api.LoadBalancer.TargetActionRequest;
import org.pragmatica.cloud.hetzner.api.Network;
import org.pragmatica.cloud.hetzner.api.Network.NetworkListResponse;
import org.pragmatica.cloud.hetzner.api.Network.NetworkResponse;
import org.pragmatica.cloud.hetzner.api.Pagination.Paged;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.cloud.hetzner.api.Server.ServerListResponse;
import org.pragmatica.cloud.hetzner.api.Server.ServerResponse;
import org.pragmatica.cloud.hetzner.api.Server.UpdateLabelsRequest;
import org.pragmatica.cloud.hetzner.api.SshKey;
import org.pragmatica.cloud.hetzner.api.SshKey.SshKeyListResponse;
import org.pragmatica.cloud.hetzner.api.SshKey.SshKeyResponse;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


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
    /// Lists servers matching a label selector (e.g., "aether-cluster=my-cluster").
    Promise<List<Server>> listServers(String labelSelector);
    /// Updates labels on a server.
    Promise<Unit> updateServerLabels(long serverId, Map<String, String> labels);
    /// Reboots a server.
    Promise<Unit> rebootServer(long serverId);
    /// Creates a new SSH key.
    Promise<SshKey> createSshKey(SshKey.CreateSshKeyRequest request);
    /// Deletes an SSH key by ID.
    Promise<Unit> deleteSshKey(long sshKeyId);
    /// Lists all SSH keys.
    Promise<List<SshKey>> listSshKeys();
    /// Lists all networks.
    Promise<List<Network>> listNetworks();
    /// Gets a network by ID.
    Promise<Network> getNetwork(long networkId);
    /// Lists all firewalls.
    Promise<List<Firewall>> listFirewalls();
    /// Lists firewalls matching a label selector (e.g., "aether-cluster=my-cluster").
    Promise<List<Firewall>> listFirewalls(String labelSelector);
    /// Creates a standalone firewall.
    Promise<Firewall> createFirewall(CreateFirewallRequest request);
    /// Replaces a firewall's entire rule set. Hetzner has no add-one-rule action.
    Promise<Unit> setFirewallRules(long firewallId, List<Firewall.Rule> rules);
    /// Deletes a firewall by ID. Fails while the firewall is still applied to any resource —
    /// detach with [#removeFirewallFromResources] first.
    Promise<Unit> deleteFirewall(long firewallId);
    /// Detaches a firewall from a server.
    Promise<Unit> removeFirewallFromResources(long firewallId, long serverId);
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
    /// Adds an IP target to a load balancer.
    Promise<Unit> addIpTarget(long loadBalancerId, String ip);
    /// Removes an IP target from a load balancer.
    Promise<Unit> removeIpTarget(long loadBalancerId, String ip);
    /// Gets a load balancer by ID.
    Promise<LoadBalancer> getLoadBalancer(long loadBalancerId);
    /// Lists all floating IPs.
    Promise<List<FloatingIp>> listFloatingIps();
    /// Gets a floating IP by ID.
    Promise<FloatingIp> getFloatingIp(long floatingIpId);
    /// Assigns a floating IP to a server.
    Promise<Unit> assignFloatingIp(long floatingIpId, long serverId);

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
    /// Upper bound on the pages one listing may read. Hetzner serves 25 entries a page by default, so this
    /// is far above any real project; it exists so a server that never ends its listing fails the call
    /// instead of running it forever.
    static final int MAX_PAGES = 100;

    @Override
    public Promise<Server> createServer(CreateServerRequest request) {
        return postJson("/servers", request, ServerResponse.class).map(ServerResponse::server);
    }

    @Override
    public Promise<Unit> deleteServer(long serverId) {
        return delete("/servers/" + serverId);
    }

    @Override
    public Promise<Server> getServer(long serverId) {
        return getJson("/servers/" + serverId, ServerResponse.class).map(ServerResponse::server);
    }

    @Override
    public Promise<List<Server>> listServers() {
        return listAll("/servers", ServerListResponse.class);
    }

    @Override
    public Promise<List<Server>> listServers(String labelSelector) {
        return listAll("/servers?label_selector=" + encodeQueryParam(labelSelector), ServerListResponse.class);
    }

    @Override
    public Promise<Unit> updateServerLabels(long serverId, Map<String, String> labels) {
        return putJsonDiscarding("/servers/" + serverId, new UpdateLabelsRequest(labels));
    }

    @Override
    public Promise<Unit> rebootServer(long serverId) {
        return postJsonDiscarding("/servers/" + serverId + "/actions/reboot", Map.of());
    }

    @Override
    public Promise<SshKey> createSshKey(SshKey.CreateSshKeyRequest request) {
        return postJson("/ssh_keys", request, SshKeyResponse.class).map(SshKeyResponse::sshKey);
    }

    @Override
    public Promise<Unit> deleteSshKey(long sshKeyId) {
        return delete("/ssh_keys/" + sshKeyId);
    }

    @Override
    public Promise<List<SshKey>> listSshKeys() {
        return listAll("/ssh_keys", SshKeyListResponse.class);
    }

    @Override
    public Promise<List<Network>> listNetworks() {
        return listAll("/networks", NetworkListResponse.class);
    }

    @Override
    public Promise<Network> getNetwork(long networkId) {
        return getJson("/networks/" + networkId, NetworkResponse.class).map(NetworkResponse::network);
    }

    @Override
    public Promise<List<Firewall>> listFirewalls() {
        return listAll("/firewalls", FirewallListResponse.class);
    }

    @Override
    public Promise<List<Firewall>> listFirewalls(String labelSelector) {
        return listAll("/firewalls?label_selector=" + encodeQueryParam(labelSelector), FirewallListResponse.class);
    }

    @Override
    public Promise<Firewall> createFirewall(CreateFirewallRequest request) {
        return postJson("/firewalls", request, FirewallResponse.class).map(FirewallResponse::firewall);
    }

    @Override
    public Promise<Unit> setFirewallRules(long firewallId, List<Firewall.Rule> rules) {
        return postJsonDiscarding("/firewalls/" + firewallId + "/actions/set_rules",
                                  SetRulesRequest.setRulesRequest(rules));
    }

    @Override
    public Promise<Unit> deleteFirewall(long firewallId) {
        return delete("/firewalls/" + firewallId);
    }

    @Override
    public Promise<Unit> removeFirewallFromResources(long firewallId, long serverId) {
        return postJsonDiscarding("/firewalls/" + firewallId + "/actions/remove_from_resources",
                                  RemoveFromResourcesRequest.removeFromServer(serverId));
    }

    @Override
    public Promise<Unit> applyFirewall(long firewallId, long serverId) {
        return postJsonDiscarding("/firewalls/" + firewallId + "/actions/apply_to_resources",
                                  ApplyToResourcesRequest.applyToServer(serverId));
    }

    @Override
    public Promise<LoadBalancer> createLoadBalancer(CreateLoadBalancerRequest request) {
        return postJson("/load_balancers", request, LoadBalancerResponse.class).map(LoadBalancerResponse::loadBalancer);
    }

    @Override
    public Promise<Unit> deleteLoadBalancer(long loadBalancerId) {
        return delete("/load_balancers/" + loadBalancerId);
    }

    @Override
    public Promise<List<LoadBalancer>> listLoadBalancers() {
        return listAll("/load_balancers", LoadBalancerListResponse.class);
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

    @Override
    public Promise<Unit> addIpTarget(long loadBalancerId, String ip) {
        return postJsonDiscarding("/load_balancers/" + loadBalancerId + "/actions/add_target",
                                  IpTargetActionRequest.ipTarget(ip));
    }

    @Override
    public Promise<Unit> removeIpTarget(long loadBalancerId, String ip) {
        return postJsonDiscarding("/load_balancers/" + loadBalancerId + "/actions/remove_target",
                                  IpTargetActionRequest.ipTarget(ip));
    }

    @Override
    public Promise<LoadBalancer> getLoadBalancer(long loadBalancerId) {
        return getJson("/load_balancers/" + loadBalancerId, LoadBalancerResponse.class).map(LoadBalancerResponse::loadBalancer);
    }

    @Override
    public Promise<List<FloatingIp>> listFloatingIps() {
        return listAll("/floating_ips", FloatingIpListResponse.class);
    }

    @Override
    public Promise<FloatingIp> getFloatingIp(long floatingIpId) {
        return getJson("/floating_ips/" + floatingIpId, FloatingIpResponse.class).map(FloatingIpResponse::floatingIp);
    }

    @Override
    public Promise<Unit> assignFloatingIp(long floatingIpId, long serverId) {
        return postJsonDiscarding("/floating_ips/" + floatingIpId + "/actions/assign",
                                  AssignRequest.assignRequest(serverId));
    }

    // --- Internal HTTP helpers ---
    /// Reads a list endpoint to its end. Hetzner pages every list (25 entries by default) and reports the
    /// next page in `meta.pagination.next_page`, `null` on the last; a caller that stops at the first page
    /// sees a silent prefix of the inventory. The first request is the bare path, so the unpaged contract
    /// is unchanged for a result that fits in one page. A failing later page fails the whole call: a
    /// partial inventory that looks complete is the failure this exists to prevent.
    private <T, P extends Paged<T>> Promise<List<T>> listAll(String path, Class<P> responseType) {
        return listFrom(path, responseType, 1, List.of());
    }

    private <T, P extends Paged<T>> Promise<List<T>> listFrom(String path,
                                                              Class<P> responseType,
                                                              int page,
                                                              List<T> collected) {
        return getJson(pagePath(path, page), responseType).flatMap(response -> continueWith(path,
                                                                                            responseType,
                                                                                            page,
                                                                                            collected,
                                                                                            response));
    }

    private <T, P extends Paged<T>> Promise<List<T>> continueWith(String path,
                                                                  Class<P> responseType,
                                                                  int page,
                                                                  List<T> collected,
                                                                  P response) {
        var all = concat(collected, response.items());

        return response.nextPage()
                       .map(next -> advance(path, responseType, page, next, all))
                       .or(() -> Promise.success(all));
    }

    /// The listing is only complete when the server says so, so a walk that cannot continue FAILS rather
    /// than returning what it has: a `next_page` that does not move forward (it would loop forever), or
    /// more pages than [#MAX_PAGES].
    private <T, P extends Paged<T>> Promise<List<T>> advance(String path,
                                                             Class<P> responseType,
                                                             int page,
                                                             int next,
                                                             List<T> collected) {
        if (next <= page) {
            return paginationFailed(path, page, "next_page " + next + " does not advance past page " + page);
        }

        if (page >= MAX_PAGES) {
            return paginationFailed(path, page, "the listing has more than the " + MAX_PAGES + "-page cap");
        }

        return listFrom(path, responseType, next, collected);
    }

    private static <T> Promise<List<T>> paginationFailed(String path, int pages, String reason) {
        return HetznerError.PaginationFailed.FACTORY.apply(endpointOf(path), pages, reason).promise();
    }

    private static String endpointOf(String path) {
        var query = path.indexOf('?');

        return query < 0
               ? path
               : path.substring(0, query);
    }

    private static <T> List<T> concat(List<T> head, List<T> tail) {
        return Stream.concat(head.stream(),
                             tail.stream())
                     .toList();
    }

    private static String pagePath(String path, int page) {
        return page == 1
               ? path
               : path + querySeparator(path) + "page=" + page;
    }

    private static String querySeparator(String path) {
        return path.contains("?")
               ? "&"
               : "?";
    }

    private <T> Promise<T> getJson(String path, Class<T> responseType) {
        return http.sendString(buildGet(path))
                   .flatMap(result -> parseResponse(result, responseType));
    }

    private <T, R> Promise<R> postJson(String path, T body, Class<R> responseType) {
        return serializeBody(body).flatMap(json -> http.sendString(buildPost(path, json)))
                            .flatMap(result -> parseResponse(result, responseType));
    }

    private <T> Promise<Unit> postJsonDiscarding(String path, T body) {
        return serializeBody(body).flatMap(json -> http.sendString(buildPost(path, json)))
                            .flatMap(this::checkSuccess);
    }

    private <T> Promise<Unit> putJsonDiscarding(String path, T body) {
        return serializeBody(body).flatMap(json -> http.sendString(buildPut(path, json)))
                            .flatMap(this::checkSuccess);
    }

    private Promise<Unit> delete(String path) {
        return http.sendString(buildDelete(path))
                   .flatMap(this::checkSuccess);
    }

    private <T> Promise<String> serializeBody(T body) {
        return mapper.writeAsString(body)
                     .async();
    }

    private <T> Promise<T> parseResponse(HttpResult<String> result, Class<T> responseType) {
        if (result.isSuccess()) {
            return mapper.readString(result.body(),
                                     responseType)
                         .async();
        }

        return HetznerError.fromResponse(result.statusCode(),
                                         result.body(),
                                         mapper)
                           .promise();
    }

    private Promise<Unit> checkSuccess(HttpResult<String> result) {
        if (result.isSuccess()) {
            return Promise.success(Unit.unit());
        }

        return HetznerError.fromResponse(result.statusCode(),
                                         result.body(),
                                         mapper)
                           .promise();
    }

    private HttpRequest buildGet(String path) {
        return authorizedRequest(path).GET()
                                .build();
    }

    private HttpRequest buildPost(String path, String jsonBody) {
        return authorizedRequest(path).POST(BodyPublishers.ofString(jsonBody))
                                .header("Content-Type", "application/json")
                                .build();
    }

    private HttpRequest buildPut(String path, String jsonBody) {
        return authorizedRequest(path).PUT(BodyPublishers.ofString(jsonBody))
                                .header("Content-Type", "application/json")
                                .build();
    }

    private HttpRequest buildDelete(String path) {
        return authorizedRequest(path).DELETE()
                                .build();
    }

    private HttpRequest.Builder authorizedRequest(String path) {
        return HttpRequest.newBuilder()
                          .uri(URI.create(config.baseUrl() + path))
                          .header("Authorization",
                                  "Bearer " + config.apiToken());
    }

    private static String encodeQueryParam(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
