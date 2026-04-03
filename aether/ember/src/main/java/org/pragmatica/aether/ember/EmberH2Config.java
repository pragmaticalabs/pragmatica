package org.pragmatica.aether.ember;

import org.pragmatica.lang.Option;


/// Configuration for embedded H2 database server in Ember.
///
/// Example TOML configuration:
/// ```
/// [database]
/// enabled = true
/// port = 9092
/// name = "forge"
/// persistent = false
/// init_script = "init.sql"
/// ```
public record EmberH2Config(boolean enabled, int port, String name, boolean persistent, Option<String> initScript) {
    public static final int DEFAULT_PORT = 9092;

    public static final String DEFAULT_NAME = "forge";

    public static final boolean DEFAULT_PERSISTENT = false;

    public static EmberH2Config emberH2Config(boolean enabled,
                                              int port,
                                              String name,
                                              boolean persistent,
                                              Option<String> initScript) {
        return new EmberH2Config(enabled, port, name, persistent, initScript);
    }

    public static EmberH2Config disabled() {
        return new EmberH2Config(false, DEFAULT_PORT, DEFAULT_NAME, DEFAULT_PERSISTENT, Option.none());
    }

    public static EmberH2Config enabledWithDefaults() {
        return new EmberH2Config(true, DEFAULT_PORT, DEFAULT_NAME, DEFAULT_PERSISTENT, Option.none());
    }

    public static EmberH2Config enabledOnPort(int port) {
        return new EmberH2Config(true, port, DEFAULT_NAME, DEFAULT_PERSISTENT, Option.none());
    }
}
