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
package org.pragmatica.cloud.hetzner.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;


/// Hetzner Cloud firewall model.
@JsonIgnoreProperties(ignoreUnknown = true)
public record Firewall(long id, String name, List<Rule> rules, Map<String, String> labels) {
    /// Inbound direction — the only direction Aether manages. Hetzner rejects `port` on protocols
    /// other than tcp/udp, and requires `destination_ips` to stay empty for inbound rules.
    public static final String DIRECTION_IN = "in";

    /// Firewall rule.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rule(String direction,
                       String protocol,
                       String port,
                       @JsonProperty("source_ips") List<String> sourceIps,
                       @JsonProperty("destination_ips") List<String> destinationIps,
                       String description) {
        /// An inbound rule opening `port`/`protocol` to `sourceCidr`.
        public static Rule inbound(int port, String protocol, String sourceCidr, String description) {
            return new Rule(DIRECTION_IN,
                            protocol,
                            Integer.toString(port),
                            List.of(sourceCidr),
                            List.of(),
                            description);
        }

        /// Identity for create-or-patch and withdraw: two rules are the same rule when they open the
        /// same port/protocol to the same CIDR. Description is deliberately excluded — re-opening an
        /// existing rule with new prose must not duplicate it.
        public boolean sameTargetAs(int otherPort, String otherProtocol, String otherCidr) {
            return DIRECTION_IN.equals(direction)
                   && Integer.toString(otherPort).equals(port)
                   && otherProtocol.equals(protocol)
                   && sourceIps != null
                   && sourceIps.size() == 1
                   && sourceIps.getFirst().equals(otherCidr);
        }
    }

    /// Wrapper for firewall list API responses.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FirewallListResponse(List<Firewall> firewalls) {}

    /// Wrapper for single-firewall API responses (create returns `{firewall, actions}`).
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FirewallResponse(Firewall firewall) {}

    /// Request to create a standalone firewall (§6.2 — Aether creates its rules as a standalone
    /// firewall associated with the source's servers). Labels are REQUIRED in practice: the
    /// out-of-band reaper (`tools/cloud-reaper.sh`) selects firewalls by label, so an unlabelled
    /// firewall is invisible to cleanup and leaks as a paid resource.
    public record CreateFirewallRequest(String name, List<Rule> rules, Map<String, String> labels) {
        public static CreateFirewallRequest createFirewallRequest(String name,
                                                                  List<Rule> rules,
                                                                  Map<String, String> labels) {
            return new CreateFirewallRequest(name, rules, labels);
        }
    }

    /// Request to REPLACE a firewall's full rule set. Hetzner exposes no add-one-rule action, so a
    /// create-or-patch caller must read the current rules and send the union — never a bare single
    /// rule, which would silently drop every other rule on the firewall.
    public record SetRulesRequest(List<Rule> rules) {
        public static SetRulesRequest setRulesRequest(List<Rule> rules) {
            return new SetRulesRequest(rules);
        }
    }

    /// Request to apply firewall to a server.
    public record ApplyToResourcesRequest(@JsonProperty("apply_to") List<ResourceTarget> applyTo) {
        /// Target resource for firewall application.
        public record ResourceTarget(String type, ServerRef server) {}

        /// Server reference by ID.
        public record ServerRef(long id) {}

        /// Factory method for applying a firewall to a server.
        public static ApplyToResourcesRequest applyToServer(long serverId) {
            return new ApplyToResourcesRequest(List.of(new ResourceTarget("server", new ServerRef(serverId))));
        }
    }

    /// Request to detach a firewall from a server. Hetzner keys this `remove_from`, NOT `apply_to` —
    /// reusing [ApplyToResourcesRequest] here silently re-attaches instead of detaching.
    public record RemoveFromResourcesRequest(
            @JsonProperty("remove_from") List<ApplyToResourcesRequest.ResourceTarget> removeFrom) {
        public static RemoveFromResourcesRequest removeFromServer(long serverId) {
            return new RemoveFromResourcesRequest(List.of(new ApplyToResourcesRequest.ResourceTarget(
                    "server",
                    new ApplyToResourcesRequest.ServerRef(serverId))));
        }
    }
}
