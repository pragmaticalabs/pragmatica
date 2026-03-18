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

package org.pragmatica.cloud.azure.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/// Azure ARM Virtual Machine resource.
@JsonIgnoreProperties(ignoreUnknown = true)
public record VirtualMachine(String id,
                              String name,
                              String location,
                              Map<String, String> tags,
                              VmProperties properties) {
    /// VM properties containing provisioning state and instance view.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VmProperties(String vmId,
                                String provisioningState,
                                InstanceViewStatus instanceView) {}

    /// Instance view containing status information.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstanceViewStatus(List<Status> statuses) {}

    /// Individual status entry (e.g., PowerState/running).
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(String code, String displayStatus) {}

    /// Wrapper for list VM responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VmListResponse(List<VirtualMachine> value) {}
}
