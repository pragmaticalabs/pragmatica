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
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/// Hetzner Cloud floating IP model.
@JsonIgnoreProperties(ignoreUnknown = true)
public record FloatingIp(long id,
                         String ip,
                         String type,
                         Long server,
                         @JsonProperty("home_location") Location homeLocation,
                         Map<String, String> labels) {
    /// Hetzner location (datacenter location).
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(String name, String city, String country) {}

    /// Request to assign a floating IP to a server.
    public record AssignRequest(long server) {
        /// Factory method for creating an assign request.
        public static AssignRequest assignRequest(long serverId) {
            return new AssignRequest(serverId);
        }
    }

    /// Wrapper for single floating IP API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FloatingIpResponse(@JsonProperty("floating_ip") FloatingIp floatingIp) {}

    /// Wrapper for floating IP list API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FloatingIpListResponse(@JsonProperty("floating_ips") List<FloatingIp> floatingIps) {}
}
