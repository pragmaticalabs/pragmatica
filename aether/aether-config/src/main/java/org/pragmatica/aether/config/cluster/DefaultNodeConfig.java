package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.StreamOps;


/// Loader for shipped node config defaults (Layers 1 and 2) from the classpath.
///
/// Layer 1 ("global default") is `defaults/aether-default.toml` — applies to all node sources.
/// Layer 2 ("per-source-type default") is `defaults/aether-{type}.toml` — overlays Layer 1 with
/// settings specific to each [SourceType].
///
/// Both files are shipped as classpath resources inside this module's JAR. A missing default
/// file is a packaging error, not a user error — `ResourceNotFound` is returned in that case.
public interface DefaultNodeConfig {
    String GLOBAL_DEFAULT_RESOURCE = "defaults/aether-default.toml";

    static Result<TomlDocument> globalDefault() {
        return loadResource(GLOBAL_DEFAULT_RESOURCE);
    }

    static Result<TomlDocument> sourceTypeDefault(SourceType type) {
        return loadResource("defaults/aether-" + type.value() + ".toml");
    }

    private static Result<TomlDocument> loadResource(String path) {
        return StreamOps.readResource(DefaultNodeConfig.class.getClassLoader(), path)
                        .flatMap(TomlParser::parse);
    }
}
