package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;


/// Firewall rule definition. S5.1.8
public record FirewallRule(int port, String protocol, String sourceCidr, Option<String> description) {
    public static FirewallRule firewallRule(int port, String protocol, String sourceCidr, Option<String> description) {
        return new FirewallRule(port, protocol, sourceCidr, description);
    }
}
