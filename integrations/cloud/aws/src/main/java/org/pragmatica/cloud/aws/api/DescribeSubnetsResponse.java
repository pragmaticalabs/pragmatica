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

import org.pragmatica.lang.Option;

import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;


/// `DescribeSubnets` — bound solely to answer "which VPC is this subnet in?".
///
/// A security group must live in the SAME VPC as the instances it will be attached to; a group created
/// in the default VPC cannot be attached to an instance launched into a subnet of another one, and
/// `RunInstances` rejects the pair. The subnet is already configured (`[cloud.compute] subnet_id`), so
/// the VPC is DERIVED from it rather than configured separately — a second knob could be set
/// inconsistently with the first, and this cannot.
@JacksonXmlRootElement(localName = "DescribeSubnetsResponse")
@JsonIgnoreProperties(ignoreUnknown = true)
public record DescribeSubnetsResponse(@JacksonXmlProperty(localName = "subnetSet") SubnetSet subnetSet) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubnetSet(@JacksonXmlElementWrapper(useWrapping = false)
                            @JacksonXmlProperty(localName = "item") List<Subnet> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subnet(@JacksonXmlProperty(localName = "subnetId") String subnetId,
                         @JacksonXmlProperty(localName = "vpcId") String vpcId) {}

    /// The VPC of the first (and, for a by-id lookup, only) subnet returned. Empty when the subnet does
    /// not exist or carries no VPC — the caller then creates the group in the account's default VPC,
    /// which is the correct placement precisely when no subnet constrains it.
    public Option<String> vpcId() {
        if (subnetSet == null || subnetSet.items() == null || subnetSet.items().isEmpty()) {
            return Option.empty();
        }

        return Option.option(subnetSet.items().getFirst().vpcId());
    }
}
