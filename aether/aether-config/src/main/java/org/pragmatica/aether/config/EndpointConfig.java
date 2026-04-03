package org.pragmatica.aether.config;

public record EndpointConfig(String host, int port, String username, String password) {
    public static EndpointConfig endpointConfig(String host, int port, String username, String password) {
        return new EndpointConfig(host, port, username, password);
    }

    @Override public String toString() {
        return "EndpointConfig[host=" + host + ", port=" + port + ", username=" + username + ", password=***]";
    }
}
