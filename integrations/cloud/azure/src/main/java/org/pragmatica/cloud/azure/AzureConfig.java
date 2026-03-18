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

package org.pragmatica.cloud.azure;

/// Configuration for the Azure Cloud API client.
public record AzureConfig(String tenantId,
                           String clientId,
                           String clientSecret,
                           String subscriptionId,
                           String resourceGroup,
                           String location,
                           String baseUrl) {
    private static final String DEFAULT_BASE_URL = "https://management.azure.com";

    /// Creates configuration with default Azure management base URL.
    public static AzureConfig azureConfig(String tenantId,
                                           String clientId,
                                           String clientSecret,
                                           String subscriptionId,
                                           String resourceGroup,
                                           String location) {
        return new AzureConfig(tenantId, clientId, clientSecret, subscriptionId, resourceGroup, location, DEFAULT_BASE_URL);
    }

    /// Creates configuration with custom base URL.
    public static AzureConfig azureConfig(String tenantId,
                                           String clientId,
                                           String clientSecret,
                                           String subscriptionId,
                                           String resourceGroup,
                                           String location,
                                           String baseUrl) {
        return new AzureConfig(tenantId, clientId, clientSecret, subscriptionId, resourceGroup, location, baseUrl);
    }

    /// Returns the ARM resource prefix for this subscription and resource group.
    public String resourcePrefix() {
        return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup + "/providers";
    }
}
