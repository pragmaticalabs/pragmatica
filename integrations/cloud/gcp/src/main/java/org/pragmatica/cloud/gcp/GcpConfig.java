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

package org.pragmatica.cloud.gcp;

/// Configuration for the GCP Cloud API client.
public record GcpConfig(String projectId, String zone, String serviceAccountEmail, String privateKeyPem, String baseUrl) {
    private static final String DEFAULT_BASE_URL = "https://compute.googleapis.com/compute/v1";

    /// Creates configuration with default GCP Compute API base URL.
    public static GcpConfig gcpConfig(String projectId, String zone, String serviceAccountEmail, String privateKeyPem) {
        return new GcpConfig(projectId, zone, serviceAccountEmail, privateKeyPem, DEFAULT_BASE_URL);
    }

    /// Creates configuration with custom base URL.
    public static GcpConfig gcpConfig(String projectId, String zone, String serviceAccountEmail, String privateKeyPem, String baseUrl) {
        return new GcpConfig(projectId, zone, serviceAccountEmail, privateKeyPem, baseUrl);
    }
}
