package org.pragmatica.aether.forge;

import org.pragmatica.lang.Option;

/**
 * Configuration for embedded H2 database server in Forge.
 * <p>
 * Example TOML configuration:
 * <pre>
 * [database]
 * enabled = true
 * port = 9092
 * name = "forge"
 * persistent = false
 * init_script = "init.sql"
 * </pre>
 */
public record ForgeH2Config(boolean enabled,
                            int port,
                            String name,
                            boolean persistent,
                            Option<String> initScript) {
    public static final int DEFAULT_PORT = 9092;
    public static final String DEFAULT_NAME = "forge";
    public static final boolean DEFAULT_PERSISTENT = false;

    /**
     * Create H2 configuration with all parameters.
     */
    public static ForgeH2Config forgeH2Config(boolean enabled,
                                              int port,
                                              String name,
                                              boolean persistent,
                                              Option<String> initScript) {
        return new ForgeH2Config(enabled, port, name, persistent, initScript);
    }

    /**
     * Create disabled H2 configuration.
     */
    public static ForgeH2Config disabled() {
        return new ForgeH2Config(false, DEFAULT_PORT, DEFAULT_NAME, DEFAULT_PERSISTENT, Option.none());
    }

    /**
     * Create enabled H2 configuration with defaults.
     */
    public static ForgeH2Config enabledWithDefaults() {
        return new ForgeH2Config(true, DEFAULT_PORT, DEFAULT_NAME, DEFAULT_PERSISTENT, Option.none());
    }

    /**
     * Create enabled H2 configuration on a specific port.
     */
    public static ForgeH2Config enabledOnPort(int port) {
        return new ForgeH2Config(true, port, DEFAULT_NAME, DEFAULT_PERSISTENT, Option.none());
    }
}
