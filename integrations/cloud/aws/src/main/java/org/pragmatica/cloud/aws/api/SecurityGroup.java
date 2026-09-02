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
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
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
                            @JacksonXmlProperty(localName = "tagSet") Instance.TagSet tagSet,
                            @JacksonXmlProperty(localName = "ipPermissions") IpPermissionSet ipPermissions) {
    /// Inbound permissions. Bound because `closeIngress` has to know whether the rule it just revoked
    /// was the LAST one: the [org.pragmatica.aether.environment.ComputeProvider#closeIngress] contract
    /// disposes the provider resource when its final rule goes, and on EC2 the revoke call reports
    /// nothing about what remains. Outbound (`ipPermissionsEgress`) is deliberately NOT bound — Aether
    /// never manages egress, and binding it would invite code that does.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IpPermissionSet(@JacksonXmlElementWrapper(useWrapping = false)
                                  @JacksonXmlProperty(localName = "item") List<IpPermission> items) {}

    /// One inbound permission. `fromPort`/`toPort` are boxed because EC2 omits them entirely for
    /// protocols that have no ports (`-1`/`icmp`), and a primitive would silently read that as port 0.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IpPermission(@JacksonXmlProperty(localName = "ipProtocol") String ipProtocol,
                               @JacksonXmlProperty(localName = "fromPort") Integer fromPort,
                               @JacksonXmlProperty(localName = "toPort") Integer toPort,
                               @JacksonXmlElementWrapper(useWrapping = false)
                               @JacksonXmlProperty(localName = "ipRanges") IpRangeSet ipRanges) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IpRangeSet(@JacksonXmlElementWrapper(useWrapping = false)
                             @JacksonXmlProperty(localName = "item") List<IpRange> items) {}

    /// `description` is bound because a REVOKE has to reproduce the stored rule exactly. EC2 accepted
    /// the rule with the description `openIngress` attached, and a revoke that omits it fails to match
    /// — answering `InvalidPermission.NotFound`, which used to be swallowed as success, so the rule
    /// silently survived. Absent for rules created without one, hence nullable.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IpRange(@JacksonXmlProperty(localName = "cidrIp") String cidrIp,
                          @JacksonXmlProperty(localName = "description") String description) {}

    /// How many inbound permissions this group carries. Absent/empty deserializes to zero rather than
    /// failing: a group with no rules is an ordinary state — it is exactly what the last revoke leaves
    /// behind, and the value this method exists to report.
    public int inboundRuleCount() {
        if (ipPermissions == null || ipPermissions.items() == null) {
            return 0;
        }

        return ipPermissions.items().size();
    }

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
