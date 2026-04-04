package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;

import java.util.List;
import java.util.Map;


/// A parsed blueprint artifact containing the blueprint definition,
/// optional resources configuration, and optional schema migrations.
///
/// @param blueprint the parsed Blueprint from blueprint.toml
/// @param resourcesConfig optional raw TOML content from resources.toml
/// @param schemaMigrations schema migration scripts keyed by datasource name
@Codec@CodecFor(Blueprint.class) public record BlueprintArtifact(Blueprint blueprint,
                                                                 Option<String> resourcesConfig,
                                                                 Map<String, List<MigrationEntry>> schemaMigrations) {
    public BlueprintArtifact {
        schemaMigrations = schemaMigrations == null
                          ? Map.of()
                          : Map.copyOf(schemaMigrations);
    }

    public static BlueprintArtifact blueprintArtifact(Blueprint blueprint,
                                                      Option<String> resourcesConfig,
                                                      Map<String, List<MigrationEntry>> schemaMigrations) {
        return new BlueprintArtifact(blueprint, resourcesConfig, Map.copyOf(schemaMigrations));
    }

    public static BlueprintArtifact blueprintArtifact(Blueprint blueprint) {
        return new BlueprintArtifact(blueprint, Option.none(), Map.of());
    }
}
