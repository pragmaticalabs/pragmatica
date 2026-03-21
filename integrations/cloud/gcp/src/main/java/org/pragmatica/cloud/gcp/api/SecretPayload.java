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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/// GCP Secret Manager secret version access response.
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecretPayload(SecretData payload) {
    /// Secret data container with base64-encoded data.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SecretData(String data) {}
}
