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

/// ARM request body for creating a Virtual Machine.
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateVmRequest(String name,
                               String location,
                               Map<String, String> tags,
                               VmRequestProperties properties) {
    /// VM creation properties.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VmRequestProperties(HardwareProfile hardwareProfile,
                                       StorageProfile storageProfile,
                                       OsProfile osProfile,
                                       NetworkProfile networkProfile) {}

    /// Hardware profile specifying VM size.
    public record HardwareProfile(String vmSize) {}

    /// Storage profile specifying OS disk and image reference.
    public record StorageProfile(ImageReference imageReference, OsDisk osDisk) {}

    /// Image reference for the OS.
    public record ImageReference(String publisher, String offer, String sku, String version) {}

    /// OS disk configuration.
    public record OsDisk(String createOption, ManagedDisk managedDisk) {}

    /// Managed disk parameters.
    public record ManagedDisk(String storageAccountType) {}

    /// OS profile with admin credentials.
    public record OsProfile(String computerName,
                             String adminUsername,
                             LinuxConfiguration linuxConfiguration) {}

    /// Linux-specific configuration.
    public record LinuxConfiguration(boolean disablePasswordAuthentication, SshConfiguration ssh) {}

    /// SSH configuration.
    public record SshConfiguration(List<SshPublicKey> publicKeys) {}

    /// SSH public key.
    public record SshPublicKey(String path, String keyData) {}

    /// Network profile with NIC references.
    public record NetworkProfile(List<NetworkInterfaceRef> networkInterfaces) {}

    /// Reference to a network interface.
    public record NetworkInterfaceRef(String id) {}

    /// Factory method for creating a VM request.
    public static CreateVmRequest createVmRequest(String name,
                                                   String location,
                                                   Map<String, String> tags,
                                                   VmRequestProperties properties) {
        return new CreateVmRequest(name, location, tags, properties);
    }
}
