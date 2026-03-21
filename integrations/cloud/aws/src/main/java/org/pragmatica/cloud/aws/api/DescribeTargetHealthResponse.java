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

package org.pragmatica.cloud.aws.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/// ELBv2 DescribeTargetHealth JSON response.
@JsonIgnoreProperties(ignoreUnknown = true)
public record DescribeTargetHealthResponse(
    @JsonProperty("TargetHealthDescriptions") List<TargetHealthDescription> targetHealthDescriptions
) {
    /// Target health description entry.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetHealthDescription(
        @JsonProperty("Target") Target target,
        @JsonProperty("TargetHealth") TargetHealthState targetHealth
    ) {}

    /// Target identifier.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Target(
        @JsonProperty("Id") String id,
        @JsonProperty("Port") int port
    ) {}

    /// Target health state.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetHealthState(
        @JsonProperty("State") String state,
        @JsonProperty("Description") String description
    ) {}

    /// Extracts flat list of TargetHealth records.
    public List<TargetHealth> toTargetHealthList() {
        return targetHealthDescriptions.stream()
                                       .map(DescribeTargetHealthResponse::toTargetHealth)
                                       .toList();
    }

    private static TargetHealth toTargetHealth(TargetHealthDescription desc) {
        return new TargetHealth(
            desc.target().id(),
            desc.target().port(),
            desc.targetHealth().state(),
            desc.targetHealth().description()
        );
    }
}
