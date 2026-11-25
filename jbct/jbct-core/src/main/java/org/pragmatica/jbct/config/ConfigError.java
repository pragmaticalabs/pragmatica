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

    /// A source root whose root-reading structure rules are enabled but whose configuration names no
    /// `[project] basePackage`. Raised by the structure rules' run (#2526), per source root.
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
                                          + ": add basePackage = \"" + suggestion
                                          + "\" to the [project] table of " + toml
                                          + ", or set [lint] mode = \"infrastructure\"");
        }
    }

    /// A source root whose `[project] basePackage` has an `io` or `shared` segment after its first: such a root would
    /// itself be an I/O or shared package of its own tree, so the structure rules cannot judge against it (#2526).
    ///
    /// @param sourceRoot  the refused source root
    /// @param basePackage the configured root package
    /// @param suggestion  the common package prefix of the root's main files, advice only
    /// @param toml        the `jbct.toml` the key belongs in
    record BasePackageHasRole(Path sourceRoot, String basePackage, String suggestion, Path toml, String message) implements ConfigError {
        public static BasePackageHasRole basePackageHasRole(Path sourceRoot,
                                                            String basePackage,
                                                            String suggestion,
                                                            Path toml) {
            return new BasePackageHasRole(sourceRoot,
                                          basePackage,
                                          suggestion,
                                          toml,
                                          "structure rules cannot judge " + sourceRoot
                                         + " against [project] basePackage \"" + basePackage
                                         + "\": a root package has no io or shared segment after its first; set basePackage = \"" + suggestion
                                         + "\" in the [project] table of " + toml
                                         + ", or set [lint] mode = \"infrastructure\"");
        }
    }

    /// A source root none of whose main files is declared in `[project] basePackage` or below it: the configured root
    /// names no part of this source root, so the structure rules cannot judge it (#2526).
    ///
    /// @param sourceRoot  the refused source root
    /// @param basePackage the configured root package
    /// @param suggestion  the common package prefix of the root's main files, advice only
    /// @param toml        the `jbct.toml` the key belongs in
    record BasePackageCoversNoFile(Path sourceRoot, String basePackage, String suggestion, Path toml, String message) implements ConfigError {
        public static BasePackageCoversNoFile basePackageCoversNoFile(Path sourceRoot,
                                                                      String basePackage,
                                                                      String suggestion,
                                                                      Path toml) {
            return new BasePackageCoversNoFile(sourceRoot,
                                               basePackage,
                                               suggestion,
                                               toml,
                                               "structure rules cannot judge " + sourceRoot
                                              + ": no main file is under [project] basePackage \"" + basePackage
                                              + "\"; set basePackage = \"" + suggestion
                                              + "\" in the [project] table of " + toml
                                              + ", or set [lint] mode = \"infrastructure\"");
        }
    }
}
