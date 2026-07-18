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

import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;


/// ELBv2 DescribeTargetHealth XML response (AWS Query protocol, API version 2015-12-01).
@JacksonXmlRootElement(localName = "DescribeTargetHealthResponse")
@JsonIgnoreProperties(ignoreUnknown = true)
public record DescribeTargetHealthResponse(@JacksonXmlProperty(localName = "DescribeTargetHealthResult") DescribeTargetHealthResult result) {
    /// Query-protocol result wrapper.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DescribeTargetHealthResult(@JacksonXmlProperty(localName = "TargetHealthDescriptions") TargetHealthDescriptions targetHealthDescriptions) {}

    /// Container for the repeated `member` target-health entries.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetHealthDescriptions(@JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "member") List<TargetHealthDescription> members) {}

    /// Target health description entry.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetHealthDescription(@JacksonXmlProperty(localName = "Target") Target target,
                                          @JacksonXmlProperty(localName = "TargetHealth") TargetHealthState targetHealth) {}

    /// Target identifier.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Target(@JacksonXmlProperty(localName = "Id") String id, @JacksonXmlProperty(localName = "Port") int port) {}

    /// Target health state.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetHealthState(@JacksonXmlProperty(localName = "State") String state,
                                    @JacksonXmlProperty(localName = "Description") String description) {}

    /// Extracts a flat list of TargetHealth records, tolerating an absent result or empty target set.
    public List<TargetHealth> toTargetHealthList() {
        if (result == null || result.targetHealthDescriptions() == null
            || result.targetHealthDescriptions().members() == null) {
            return List.of();
        }

        return result.targetHealthDescriptions()
                     .members()
                     .stream()
                     .map(DescribeTargetHealthResponse::toTargetHealth)
                     .toList();
    }

    private static TargetHealth toTargetHealth(TargetHealthDescription desc) {
        return new TargetHealth(desc.target().id(),
                                desc.target().port(),
                                desc.targetHealth().state(),
                                desc.targetHealth().description());
    }
}
