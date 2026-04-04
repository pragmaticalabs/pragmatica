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
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.pragmatica.lang.Option;

/// Request to insert a new GCP Compute Engine instance.
@JsonIgnoreProperties(ignoreUnknown = true)
public record InsertInstanceRequest(String name,
                                    String machineType,
                                    List<Disk> disks,
                                    List<NetworkInterfaceConfig> networkInterfaces,
                                    Map<String, String> labels,
                                    Metadata metadata,
                                    @JsonIgnore Option<String> zoneOverride) {
    /// Convenience constructor without zone override.
    public InsertInstanceRequest(String name,
                                 String machineType,
                                 List<Disk> disks,
                                 List<NetworkInterfaceConfig> networkInterfaces,
                                 Map<String, String> labels,
                                 Metadata metadata) {
        this(name, machineType, disks, networkInterfaces, labels, metadata, Option.empty());
    }
    /// Disk configuration for instance creation.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Disk(boolean autoDelete,
                       boolean boot,
                       InitializeParams initializeParams) {}

    /// Parameters for initializing a disk from an image.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InitializeParams(String sourceImage, long diskSizeGb, String diskType) {}

    /// Network interface configuration for instance creation.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NetworkInterfaceConfig(String network, String subnetwork, List<AccessConfig> accessConfigs) {}

    /// Access configuration for network interfaces (external IP).
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessConfig(String name, String type) {}

    /// Instance metadata (key-value pairs).
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(List<MetadataItem> items) {}

    /// Single metadata key-value pair.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MetadataItem(String key, String value) {}
}
