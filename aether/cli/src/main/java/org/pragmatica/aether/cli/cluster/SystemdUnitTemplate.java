package org.pragmatica.aether.cli.cluster;
/// Generates systemd unit files for JVM-based (non-Docker) Aether node deployments.
///
/// Template variables: javaOpts, jarPath, configPath, user, group.
sealed interface SystemdUnitTemplate {
    record unused() implements SystemdUnitTemplate {}

    String DEFAULT_JAVA_OPTS = "-Xmx4g -XX:+UseZGC";
    String DEFAULT_JAR_PATH = "/opt/aether/aether-node.jar";
    String DEFAULT_CONFIG_PATH = "/opt/aether/config/aether.toml";
    String DEFAULT_USER = "aether";
    String DEFAULT_GROUP = "aether";

    /// Generate a systemd unit file with explicit parameters.
    static String generate(String javaOpts,
                           String jarPath,
                           String configPath,
                           String user,
                           String group) {
        var sb = new StringBuilder();
        sb.append("[Unit]\n");
        sb.append("Description=Aether Node %i\n");
        sb.append("After=network-online.target\n");
        sb.append("Wants=network-online.target\n");
        sb.append('\n');
        sb.append("[Service]\n");
        sb.append("Type=simple\n");
        sb.append("User=")
          .append(user)
          .append('\n');
        sb.append("Group=")
          .append(group)
          .append('\n');
        sb.append("ExecStart=/usr/bin/java ")
          .append(javaOpts)
          .append(" -jar ")
          .append(jarPath)
          .append(" --config=")
          .append(configPath)
          .append('\n');
        sb.append("Restart=on-failure\n");
        sb.append("RestartSec=5s\n");
        sb.append("StandardOutput=journal\n");
        sb.append("StandardError=journal\n");
        sb.append("Environment=JAVA_OPTS=")
          .append(javaOpts)
          .append('\n');
        sb.append('\n');
        sb.append("[Install]\n");
        sb.append("WantedBy=multi-user.target\n");
        return sb.toString();
    }

    /// Generate a systemd unit file with default values.
    static String generateDefault() {
        return generate(DEFAULT_JAVA_OPTS, DEFAULT_JAR_PATH, DEFAULT_CONFIG_PATH, DEFAULT_USER, DEFAULT_GROUP);
    }
}
