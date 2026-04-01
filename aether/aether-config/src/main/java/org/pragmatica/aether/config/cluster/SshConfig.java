package org.pragmatica.aether.config.cluster;
public record SshConfig( String user,
                         String keyPath,
                         int port) {
    /// Factory method with explicit port.
    public static SshConfig sshConfig(String user, String keyPath, int port) {
        return new SshConfig(user, keyPath, port);
    }

    /// Factory method with default SSH port (22).
    public static SshConfig sshConfig(String user, String keyPath) {
        return sshConfig(user, keyPath, 22);
    }
}
