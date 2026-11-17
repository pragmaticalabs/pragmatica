package org.pragmatica.jbct.config;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

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

    /// Several config layers that could not be used: one line per layer, each naming its file.
    record LayersInvalid(List<Cause> layers, String message) implements ConfigError {
        public static LayersInvalid layersInvalid(List<Cause> layers) {
            return new LayersInvalid(List.copyOf(layers),
                                     layers.stream().map(Cause::message).collect(Collectors.joining("\n")));
        }
    }

    /// A `[lint] mode`, `--mode` or `-Djbct.mode` value that names no [JbctMode].
    record ModeUnknown(String raw, String message) implements ConfigError {
        public static ModeUnknown modeUnknown(String raw) {
            return new ModeUnknown(raw, "Unknown JBCT mode \"" + raw + "\": expected " + JbctMode.ALLOWED);
        }
    }

    /// A mode that exists but that no lint or format entry point honours yet (`format-only`, `disabled`): accepted in
    /// the same change that honours it, so no build ever accepts a mode it does not apply.
    record ModeNotYetSupported(JbctMode mode, String message) implements ConfigError {
        public static ModeNotYetSupported modeNotYetSupported(JbctMode mode) {
            return new ModeNotYetSupported(mode,
                                           "JBCT mode \"" + mode.token()
                                           + "\" is not yet supported: use \"business\" or \"infrastructure\"");
        }
    }

    /// A source root whose root-reading structure rules are enabled but whose configuration names no
    /// `[project] basePackage`. Declared here; raised by the structure rules' run (#2526).
    ///
    /// @param sourceRoot the refused source root
    /// @param suggestion the common package prefix of the root's main files, advice only
    /// @param toml       the `jbct.toml` the key belongs in
    record BasePackageRequired(Path sourceRoot, String suggestion, Path toml, String message) implements ConfigError {
        public static BasePackageRequired basePackageRequired(Path sourceRoot, String suggestion, Path toml) {
            return new BasePackageRequired(sourceRoot,
                                           suggestion,
                                           toml,
                                           "structure rules need [project] basePackage for " + sourceRoot
                                           + ": add basePackage = \"" + suggestion + "\" to the [project] table of " + toml
                                           + ", or set [lint] mode = \"infrastructure\"");
        }
    }
}
