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

/// EC2 RunInstances XML response wrapper.
@JacksonXmlRootElement(localName = "RunInstancesResponse")
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunInstancesResponse(
    @JacksonXmlProperty(localName = "instancesSet") InstancesSet instancesSet
) {
    /// Container for launched instances.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstancesSet(
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "item") List<Instance> items
    ) {}

    /// Returns the list of launched instances.
    public List<Instance> instances() {
        return instancesSet.items();
    }
}
