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

/// Hetzner Cloud server model.
@JsonIgnoreProperties(ignoreUnknown = true)
public record Server(long id,
                     String name,
                     String status,
                     @JsonProperty("server_type") ServerType serverType,
                     Image image,
                     @JsonProperty("public_net") PublicNet publicNet,
                     @JsonProperty("private_net") List<PrivateNet> privateNet) {
    /// Server hardware type.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerType(long id, String name, String description, int cores, double memory, int disk) {}

    /// Server image (OS).
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(long id, String name, String description, @JsonProperty("os_flavor") String osFlavor) {}

    /// Public network configuration.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublicNet(Ipv4 ipv4, Ipv6 ipv6) {}

    /// IPv4 address.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ipv4(String ip) {}

    /// IPv6 network.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ipv6(String ip) {}

    /// Private network attachment.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrivateNet(long network, String ip) {}

    /// Request to create a new server.
    public record CreateServerRequest(String name,
                                      @JsonProperty("server_type") String serverType,
                                      String image,
                                      @JsonProperty("ssh_keys") List<Long> sshKeys,
                                      List<Long> networks,
                                      List<FirewallRef> firewalls,
                                      String location,
                                      @JsonProperty("user_data") String userData,
                                      @JsonProperty("start_after_create") boolean startAfterCreate) {
        /// Firewall reference for server creation.
        public record FirewallRef(long firewall) {}

        /// Factory method for creating a server request.
        public static CreateServerRequest createServerRequest(String name,
                                                              String serverType,
                                                              String image,
                                                              List<Long> sshKeys,
                                                              List<Long> networks,
                                                              List<Long> firewalls,
                                                              String location,
                                                              String userData,
                                                              boolean startAfterCreate) {
            return new CreateServerRequest(name,
                                           serverType,
                                           image,
                                           sshKeys,
                                           networks,
                                           firewalls.stream()
                                                    .map(FirewallRef::new)
                                                    .toList(),
                                           location,
                                           userData,
                                           startAfterCreate);
        }
    }

    /// Wrapper for single-server API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerResponse(Server server, Action action) {}

    /// Wrapper for server list API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerListResponse(List<Server> servers) {}
}
