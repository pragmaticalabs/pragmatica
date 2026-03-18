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

/// Request body for Azure Resource Graph query.
public record ResourceGraphRequest(List<String> subscriptions, String query) {
    /// Factory method for creating a resource graph request.
    public static ResourceGraphRequest resourceGraphRequest(List<String> subscriptions, String query) {
        return new ResourceGraphRequest(subscriptions, query);
    }
}
