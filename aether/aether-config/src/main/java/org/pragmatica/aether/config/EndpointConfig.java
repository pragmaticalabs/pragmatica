package org.pragmatica.aether.config;
/// Configuration for an infrastructure endpoint (database, cache, message queue, etc.).
/// Parsed from [endpoints.*] sections in aether.toml.
///
/// @param host     the hostname or IP address
/// @param port     the port number
/// @param username the authentication username
/// @param password the authentication password (may contain secret references)
public record EndpointConfig(String host, int port, String username, String password) {
    /// Factory method following JBCT naming convention.
    public static EndpointConfig endpointConfig(String host, int port, String username, String password) {
        return new EndpointConfig(host, port, username, password);
    }

    @Override
    public String toString() {
        return "EndpointConfig[host=" + host + ", port=" + port + ", username=" + username + ", password=***]";
    }
}
