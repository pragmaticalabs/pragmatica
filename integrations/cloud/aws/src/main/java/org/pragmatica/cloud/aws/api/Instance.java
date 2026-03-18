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

/// EC2 instance model.
@JsonIgnoreProperties(ignoreUnknown = true)
public record Instance(
    @JacksonXmlProperty(localName = "instanceId") String instanceId,
    @JacksonXmlProperty(localName = "instanceType") String instanceType,
    @JacksonXmlProperty(localName = "imageId") String imageId,
    @JacksonXmlProperty(localName = "privateIpAddress") String privateIpAddress,
    @JacksonXmlProperty(localName = "publicIpAddress") String publicIpAddress,
    @JacksonXmlProperty(localName = "instanceState") InstanceState instanceState,
    @JacksonXmlProperty(localName = "tagSet") TagSet tagSet
) {
    /// EC2 instance state.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstanceState(
        @JacksonXmlProperty(localName = "name") String name,
        @JacksonXmlProperty(localName = "code") int code
    ) {}

    /// Container for instance tags.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TagSet(
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "item") List<Tag> items
    ) {}

    /// EC2 resource tag.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tag(
        @JacksonXmlProperty(localName = "key") String key,
        @JacksonXmlProperty(localName = "value") String value
    ) {}
}
