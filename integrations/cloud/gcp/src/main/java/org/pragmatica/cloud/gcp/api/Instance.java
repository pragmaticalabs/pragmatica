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

package org.pragmatica.cloud.gcp.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/// GCP Compute Engine instance model.
@JsonIgnoreProperties(ignoreUnknown = true)
public record Instance(String name,
                       String status,
                       String zone,
                       List<NetworkInterface> networkInterfaces,
                       Map<String, String> labels) {
    /// Network interface attached to an instance.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NetworkInterface(String networkIP, String network) {}

    /// Wrapper for single-instance API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstanceResponse(String name,
                                   String status,
                                   String zone,
                                   List<NetworkInterface> networkInterfaces,
                                   Map<String, String> labels) {}

    /// Wrapper for instance list API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstanceListResponse(List<Instance> items) {}
}
