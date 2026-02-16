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

/// Hetzner Cloud firewall model.
@JsonIgnoreProperties(ignoreUnknown = true)
public record Firewall(long id, String name, List<Rule> rules) {
    /// Firewall rule.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rule(String direction,
                       String protocol,
                       String port,
                       @JsonProperty("source_ips") List<String> sourceIps,
                       @JsonProperty("destination_ips") List<String> destinationIps) {}

    /// Wrapper for firewall list API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FirewallListResponse(List<Firewall> firewalls) {}

    /// Request to apply firewall to a server.
    public record ApplyToResourcesRequest(@JsonProperty("apply_to") List<ResourceTarget> applyTo) {
        /// Target resource for firewall application.
        public record ResourceTarget(String type, ServerRef server) {}

        /// Server reference by ID.
        public record ServerRef(long id) {}

        /// Factory method for applying a firewall to a server.
        public static ApplyToResourcesRequest applyToServer(long serverId) {
            return new ApplyToResourcesRequest(List.of(new ResourceTarget("server", new ServerRef(serverId))));
        }
    }
}
