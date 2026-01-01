package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.routing.SliceSpec;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for blueprint DSL files using TOML format.
 *
 * <p>Blueprints define which slices to deploy and how many instances.
 * Routes are self-registered by slices during activation via RouteRegistry.
 *
 * <p>Example format:
 * <pre>
 * id = "my-app:1.0.0"
 *
 * [slices.user-service]
 * artifact = "org.example:user-service:1.0.0"
 * instances = 2
 *
 * [slices.order-service]
 * artifact = "org.example:order-service:1.0.0"
 * instances = 3
 * </pre>
 */
public interface BlueprintParser {
    Fn1<Cause, String>FILE_ERROR = Causes.forOneValue("Failed to read file: %s");
    Cause MISSING_ID = Causes.cause("Missing blueprint id");
    Fn1<Cause, String>INVALID_SLICE = Causes.forOneValue("Invalid slice definition: %s");
    Fn1<Cause, String>MISSING_ARTIFACT = Causes.forOneValue("Missing artifact for slice: %s");
    Fn1<Cause, String>INVALID_ARTIFACT = Causes.forOneValue("Invalid artifact format: %s");

    static Result<Blueprint> parse(String dsl) {
        if (dsl == null || dsl.isBlank()) {
            return MISSING_ID.result();
        }
        return TomlParser.parse(dsl)
                         .mapError(cause -> Causes.cause("TOML parse error: " + cause.message()))
                         .flatMap(BlueprintParser::parseDocument);
    }

    static Result<Blueprint> parseFile(Path path) {
        try{
            var content = Files.readString(path);
            return parse(content);
        } catch (IOException e) {
            return FILE_ERROR.apply(e.getMessage())
                             .result();
        }
    }

    private static Result<Blueprint> parseDocument(TomlDocument doc) {
        // Get blueprint ID from root
        var idOpt = doc.getString("", "id");
        if (idOpt.isEmpty()) {
            return MISSING_ID.result();
        }
        return BlueprintId.blueprintId(idOpt.unwrap())
                          .flatMap(id -> parseSlices(doc)
                                         .map(slices -> Blueprint.blueprint(id, slices)));
    }

    private static Result<List<SliceSpec>> parseSlices(TomlDocument doc) {
        var slices = new ArrayList<SliceSpec>();
        // Find all sections starting with "slices."
        for (var sectionName : doc.sectionNames()) {
            if (sectionName.startsWith("slices.")) {
                var sliceName = sectionName.substring("slices.".length());
                var result = parseSliceSection(doc, sectionName, sliceName);
                if (result.isFailure()) {
                    return result.map(_ -> null);
                }
                slices.add(result.unwrap());
            }
        }
        return Result.success(slices);
    }

    private static Result<SliceSpec> parseSliceSection(TomlDocument doc, String section, String sliceName) {
        var artifactOpt = doc.getString(section, "artifact");
        if (artifactOpt.isEmpty()) {
            return MISSING_ARTIFACT.apply(sliceName)
                                   .result();
        }
        var instanceCount = doc.getInt(section, "instances")
                               .or(1);
        return Artifact.artifact(artifactOpt.unwrap())
                       .mapError(_ -> INVALID_ARTIFACT.apply(artifactOpt.unwrap()))
                       .flatMap(artifact -> SliceSpec.sliceSpec(artifact, instanceCount));
    }
}
