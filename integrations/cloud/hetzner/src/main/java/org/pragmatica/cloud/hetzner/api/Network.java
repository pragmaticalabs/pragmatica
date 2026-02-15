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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/// Hetzner Cloud network model.
@JsonIgnoreProperties(ignoreUnknown = true)
public record Network(long id, String name, @JsonProperty("ip_range") String ipRange, List<Subnet> subnets) {

    /// Network subnet.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subnet(String type, @JsonProperty("ip_range") String ipRange,
                         @JsonProperty("network_zone") String networkZone, String gateway) {}

    /// Wrapper for single-network API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NetworkResponse(Network network) {}

    /// Wrapper for network list API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NetworkListResponse(List<Network> networks) {}
}
