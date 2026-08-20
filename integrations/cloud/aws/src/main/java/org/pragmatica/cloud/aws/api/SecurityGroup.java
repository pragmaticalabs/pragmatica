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

import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;


/// EC2 security group model.
///
/// Binds only the fields callers select on — id, name and tags; the remaining `securityGroupInfo`
/// members (owner, description, vpc, permission sets) are ignored on the wire. Tags reuse
/// [Instance.TagSet]: EC2 emits one `tagSet` shape for every taggable resource, so the binding is
/// declared once rather than duplicated per resource type.
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecurityGroup(@JacksonXmlProperty(localName = "groupId") String groupId,
                            @JacksonXmlProperty(localName = "groupName") String groupName,
                            @JacksonXmlProperty(localName = "tagSet") Instance.TagSet tagSet) {
    /// Tags as a key/value map, tolerating an absent tag set (`<tagSet />` deserializes with a null
    /// item list — an untagged group, not a malformed response).
    public Map<String, String> tags() {
        if (tagSet == null || tagSet.items() == null) {
            return Map.of();
        }

        return tagSet.items()
                     .stream()
                     .collect(Collectors.toMap(Instance.Tag::key, Instance.Tag::value));
    }
}
