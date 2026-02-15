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

/// Hetzner Cloud SSH key model.
@JsonIgnoreProperties(ignoreUnknown = true)
public record SshKey(long id, String name, String fingerprint, @JsonProperty("public_key") String publicKey) {

    /// Request to create a new SSH key.
    public record CreateSshKeyRequest(String name, @JsonProperty("public_key") String publicKey) {
        public static CreateSshKeyRequest createSshKeyRequest(String name, String publicKey) {
            return new CreateSshKeyRequest(name, publicKey);
        }
    }

    /// Wrapper for single SSH key API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SshKeyResponse(@JsonProperty("ssh_key") SshKey sshKey) {}

    /// Wrapper for SSH key list API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SshKeyListResponse(@JsonProperty("ssh_keys") List<SshKey> sshKeys) {}
}
