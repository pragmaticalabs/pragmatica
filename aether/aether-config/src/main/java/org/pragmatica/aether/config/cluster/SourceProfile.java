package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;


/// Source profile definition. S5.1
public record SourceProfile(String name,
                            SourceType type,
                            Option<CloudProviderName> provider,
                            Option<String> credentials,
                            Option<String> region,
                            Option<String> zone,
                            Option<String> user,
                            Option<String> key,
                            Option<Integer> sshPort,
                            LoadBalancerMode loadBalancer,
                            List<String> loadBalancerIps,
                            Option<String> loadBalancerEndpoint,
                            Map<String, String> databases,
                            Map<NodeRole, RoleSubTable> roles,
                            List<FirewallRule> firewallRules) {
    public SourceProfile {
        loadBalancerIps = List.copyOf(loadBalancerIps);
        databases = Map.copyOf(databases);
        roles = Map.copyOf(roles);
        firewallRules = List.copyOf(firewallRules);
    }

    public static SourceProfile sourceProfile(String name,
                                              SourceType type,
                                              Option<CloudProviderName> provider,
                                              Option<String> credentials,
                                              Option<String> region,
                                              Option<String> zone,
                                              Option<String> user,
                                              Option<String> key,
                                              Option<Integer> sshPort,
                                              LoadBalancerMode loadBalancer,
                                              List<String> loadBalancerIps,
                                              Option<String> loadBalancerEndpoint,
                                              Map<String, String> databases,
                                              Map<NodeRole, RoleSubTable> roles,
                                              List<FirewallRule> firewallRules) {
        return new SourceProfile(name,
                                 type,
                                 provider,
                                 credentials,
                                 region,
                                 zone,
                                 user,
                                 key,
                                 sshPort,
                                 loadBalancer,
                                 loadBalancerIps,
                                 loadBalancerEndpoint,
                                 databases,
                                 roles,
                                 firewallRules);
    }
}
