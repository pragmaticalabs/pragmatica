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

package org.pragmatica.cloud.hetzner.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/// Hetzner Cloud load balancer model.
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoadBalancer(long id,
                           String name,
                           @JsonProperty("load_balancer_type") LbType loadBalancerType,
                           Algorithm algorithm,
                           List<Target> targets) {
    /// Load balancer type.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LbType(long id, String name, String description) {}

    /// Load balancing algorithm.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Algorithm(String type) {}

    /// Load balancer target.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Target(String type, TargetServer server, @JsonProperty("ip") TargetIp ip) {}

    /// IP reference within a target.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetIp(String ip) {}

    /// Server reference within a target.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetServer(long id) {}

    /// Service configuration for load balancer creation.
    public record Service(String protocol,
                          @JsonProperty("listen_port") int listenPort,
                          @JsonProperty("destination_port") int destinationPort) {}

    /// Request to create a load balancer.
    public record CreateLoadBalancerRequest(@JsonProperty("load_balancer_type") String loadBalancerType,
                                            String name,
                                            Algorithm algorithm,
                                            String location,
                                            long network,
                                            List<Service> services,
                                            List<TargetRequest> targets) {
        /// Target specification for load balancer creation.
        public record TargetRequest(String type, TargetServer server) {}

        /// Factory method for creating a load balancer request.
        public static CreateLoadBalancerRequest createLoadBalancerRequest(String loadBalancerType,
                                                                          String name,
                                                                          String algorithmType,
                                                                          String location,
                                                                          long network,
                                                                          List<Service> services,
                                                                          List<Long> serverIds) {
            return new CreateLoadBalancerRequest(loadBalancerType,
                                                 name,
                                                 new Algorithm(algorithmType),
                                                 location,
                                                 network,
                                                 services,
                                                 serverIds.stream()
                                                          .map(id -> new TargetRequest("server",
                                                                                       new TargetServer(id)))
                                                          .toList());
        }
    }

    /// Request to add/remove a target.
    public record TargetActionRequest(String type, TargetServer server) {
        /// Factory method for a server target action.
        public static TargetActionRequest serverTarget(long serverId) {
            return new TargetActionRequest("server", new TargetServer(serverId));
        }
    }

    /// Request to add/remove an IP target.
    public record IpTargetActionRequest(String type, Ip ip) {
        /// IP address within an IP target request.
        public record Ip(String ip) {}

        /// Factory method for an IP target action.
        public static IpTargetActionRequest ipTarget(String ipAddress) {
            return new IpTargetActionRequest("ip", new Ip(ipAddress));
        }
    }

    /// Wrapper for single load balancer API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoadBalancerResponse(@JsonProperty("load_balancer") LoadBalancer loadBalancer) {}

    /// Wrapper for load balancer list API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoadBalancerListResponse(@JsonProperty("load_balancers") List<LoadBalancer> loadBalancers) {}
}
