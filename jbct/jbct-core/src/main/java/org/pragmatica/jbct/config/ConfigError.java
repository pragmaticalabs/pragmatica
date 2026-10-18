package org.pragmatica.jbct.config;

import java.nio.file.Path;

import org.pragmatica.lang.Cause;


/// Failures of loading JBCT configuration (#1455): a layer that cannot be used is a refused load, not a
/// layer to skip.
public sealed interface ConfigError extends Cause {
    /// One config layer (`~/.jbct/config.toml`, a `jbct.toml`, or the explicit `--config` file) that
    /// could not be read, parsed or typed. The message names the file and carries the origin's text
    /// (syntax error with its line, or the wrong-typed key with its value): this cause is shown to the
    /// person running the tool, not to a remote client, so the origin is deliberately printed.
    record LayerInvalid(Path layer, Cause origin, String message) implements ConfigError, Cause.Wrapped {
        public static LayerInvalid layerInvalid(Path layer, Cause origin) {
            return new LayerInvalid(layer, origin, "Invalid JBCT configuration " + layer + ": " + origin.message());
        }
    }
}
