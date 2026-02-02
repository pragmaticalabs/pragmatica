package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
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
import java.util.Map;

/**
 * Parser for blueprint DSL files using TOML format (RFC-0005).
 *
 * <p>Blueprints define which slices to deploy and how many instances.
 * Routes are self-registered by slices during activation via RouteRegistry.
 *
 * <p>Example format:
 * <pre>
 * id = "org.example:commerce:1.0.0"
 *
 * [[slices]]
 * artifact = "org.example:user-service:1.0.0"
 * instances = 2
 *
 * [[slices]]
 * artifact = "org.example:order-service:1.0.0"
 * instances = 3
 * </pre>
 */
public interface BlueprintParser {
    Fn1<Cause, String> FILE_ERROR = Causes.forOneValue("Failed to read file: %s");
    Cause MISSING_ID = Causes.cause("Missing blueprint id");
    Fn1<Cause, String> INVALID_SLICE = Causes.forOneValue("Invalid slice definition: %s");
    Fn1<Cause, String> MISSING_ARTIFACT = Causes.forOneValue("Missing artifact for slice: %s");
    Fn1<Cause, String> INVALID_ARTIFACT = Causes.forOneValue("Invalid artifact format: %s");

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
                          .flatMap(id -> parseSlices(doc).flatMap(slices -> Blueprint.blueprint(id, slices)));
    }

    private static Result<List<SliceSpec>> parseSlices(TomlDocument doc) {
        // RFC-0005: Parse [[slices]] array format
        return doc.getTableArray("slices")
                  .fold(() -> Result.success(List.of()),
                        BlueprintParser::parseSliceArray);
    }

    private static Result<List<SliceSpec>> parseSliceArray(List<Map<String, Object>> sliceEntries) {
        var slices = new ArrayList<SliceSpec>();
        var index = 0;
        for (var entry : sliceEntries) {
            var result = parseSliceEntry(entry, index);
            if (result.isFailure()) {
                return result.map(_ -> null);
            }
            slices.add(result.unwrap());
            index++;
        }
        return Result.success(slices);
    }

    private static Result<SliceSpec> parseSliceEntry(Map<String, Object> entry, int index) {
        var artifactObj = entry.get("artifact");
        if (artifactObj == null) {
            return MISSING_ARTIFACT.apply("slices[" + index + "]")
                                   .result();
        }
        var artifactStr = artifactObj.toString();
        var instanceCount = entry.get("instances") instanceof Number n
                            ? n.intValue()
                            : 1;
        return Artifact.artifact(artifactStr)
                       .mapError(_ -> INVALID_ARTIFACT.apply(artifactStr))
                       .flatMap(artifact -> SliceSpec.sliceSpec(artifact, instanceCount));
    }
}
