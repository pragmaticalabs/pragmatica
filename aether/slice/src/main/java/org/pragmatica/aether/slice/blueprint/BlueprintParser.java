package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.DeploymentConfig.CanaryStageConfig;
import org.pragmatica.aether.slice.blueprint.DeploymentConfig.Strategy;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;

/// Parser for blueprint DSL files using TOML format (RFC-0005).
///
///
/// Blueprints define which slices to deploy and how many instances.
/// Routes are self-registered by slices during activation via RouteRegistry.
///
///
/// Example format:
/// ```
/// id = "org.example:commerce:1.0.0"
///
/// [[slices]]
/// artifact = "org.example:user-service:1.0.0"
/// instances = 2
///
/// [[slices]]
/// artifact = "org.example:order-service:1.0.0"
/// instances = 3
///
/// [deployment]
/// strategy = "canary"
/// max_error_rate = 0.02
/// max_latency_ms = 300
///
/// [[deployment.canary.stages]]
/// traffic_percent = 5
/// observation_minutes = 5
/// ```
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-LAM-01", "JBCT-LAM-02", "JBCT-LAM-03", "JBCT-NEST-01", "JBCT-UTIL-02", "JBCT-ZONE-03", "JBCT-RET-05"})
public interface BlueprintParser {
    Fn1<Cause, String> FILE_ERROR = Causes.forOneValue("Failed to read file: %s");
    Cause MISSING_ID = cause("Missing blueprint id");
    Fn1<Cause, String> INVALID_SLICE = Causes.forOneValue("Invalid slice definition: %s");
    Fn1<Cause, String> MISSING_ARTIFACT = Causes.forOneValue("Missing artifact for slice: %s");
    Fn1<Cause, String> INVALID_ARTIFACT = Causes.forOneValue("Invalid artifact format: %s");

    static Result<Blueprint> parse(String dsl) {
        return option(dsl).filter(s -> !s.isBlank())
                     .toResult(MISSING_ID)
                     .flatMap(content -> TomlParser.parse(content).mapError(cause -> cause("TOML parse error: " + cause.message()))
                                                         .flatMap(BlueprintParser::parseDocument));
    }

    static Result<Blueprint> parseFile(Path path) {
        try {
            var content = Files.readString(path);
            return parse(content);
        }





























        catch (IOException e) {
            return FILE_ERROR.apply(e.getMessage()).result();
        }
    }

    private static Result<Blueprint> parseDocument(TomlDocument doc) {
        // Get blueprint ID from root
        var idOpt = doc.getString("", "id");
        if ( idOpt.isEmpty()) {
        return MISSING_ID.result();}
        var securityOverrides = parseSecurityOverrides(doc);
        return BlueprintId.blueprintId(idOpt.unwrap())
        .flatMap(id -> parseSlices(doc).flatMap(slices -> Blueprint.blueprint(id,
                                                                              slices,
                                                                              parseDeploymentConfig(doc),
                                                                              securityOverrides)));
    }

    private static Option<DeploymentConfig> parseDeploymentConfig(TomlDocument doc) {
        var strategyOpt = doc.getString("deployment", "strategy");
        if ( strategyOpt.isEmpty()) {
        return none();}
        var strategy = parseStrategy(strategyOpt.unwrap());
        var maxErrorRate = doc.getDouble("deployment", "max_error_rate").or(0.01);
        var maxLatencyMs = doc.getLong("deployment", "max_latency_ms").or(500L);
        var drainTimeoutMs = doc.getLong("deployment", "drain_timeout_ms").or(300_000L);
        var stages = parseCanaryStages(doc);
        var schemaRequired = doc.getBoolean("deployment", "schema_required").or(true);
        return some(DeploymentConfig.deploymentConfig(strategy,
                                                      stages,
                                                      maxErrorRate,
                                                      maxLatencyMs,
                                                      drainTimeoutMs,
                                                      schemaRequired));
    }

    private static Strategy parseStrategy(String raw) {
        return switch (raw.toLowerCase()) {case "canary" -> Strategy.CANARY;case "blue-green", "blue_green" -> Strategy.BLUE_GREEN;default -> Strategy.ROLLING;};
    }

    private static List<CanaryStageConfig> parseCanaryStages(TomlDocument doc) {
        return doc.getTableArray("deployment.canary.stages").map(BlueprintParser::toCanaryStageConfigs)
                                .or(DeploymentConfig.defaultCanaryStages());
    }

    private static List<CanaryStageConfig> toCanaryStageConfigs(List<Map<String, Object>> entries) {
        return entries.stream().map(BlueprintParser::toCanaryStageConfig)
                             .toList();
    }

    private static CanaryStageConfig toCanaryStageConfig(Map<String, Object> entry) {
        var trafficPercent = entry.get("traffic_percent") instanceof Number n
                             ? n.intValue()
                             : 5;
        var observationMinutes = entry.get("observation_minutes") instanceof Number n
                                 ? n.intValue()
                                 : 5;
        return CanaryStageConfig.canaryStageConfig(trafficPercent, observationMinutes);
    }

    private static Result<List<SliceSpec>> parseSlices(TomlDocument doc) {
        // RFC-0005: Parse [[slices]] array format
        return doc.getTableArray("slices").map(BlueprintParser::parseSliceArray)
                                .or(success(List.of()));
    }

    private static Result<List<SliceSpec>> parseSliceArray(List<Map<String, Object>> sliceEntries) {
        var slices = new ArrayList<SliceSpec>();
        var index = 0;
        for ( var entry : sliceEntries) {
            var result = parseSliceEntry(entry, index);
            if ( result.isFailure()) {
            return result.map(_ -> List.<SliceSpec>of());}
            slices.add(result.unwrap());
            index++;
        }
        return success(slices);
    }

    private static Result<SliceSpec> parseSliceEntry(Map<String, Object> entry, int index) {
        return option(entry.get("artifact")).toResult(MISSING_ARTIFACT.apply("slices[" + index + "]"))
                     .flatMap(artifactObj -> {
                                  var artifactStr = artifactObj.toString();
                                  var instanceCount = entry.get("instances") instanceof Number n
                                                      ? n.intValue()
                                                      : 1;
                                  return Artifact.artifact(artifactStr).mapError(_ -> INVALID_ARTIFACT.apply(artifactStr))
                                                          .flatMap(artifact -> parseMinAvailable(entry, instanceCount)
        .flatMap(minAvail -> SliceSpec.sliceSpec(artifact, instanceCount, minAvail)));
                              });
    }

    private static Result<Integer> parseMinAvailable(Map<String, Object> entry, int instanceCount) {
        if ( entry.get("minAvailable") instanceof Number n) {
        return success(n.intValue());}
        return success(Math.ceilDiv(instanceCount, 2));
    }

    /// Known top-level sections in blueprint TOML.
    Set<String> KNOWN_SECTIONS = Set.of("",
                                        "slices",
                                        "security",
                                        "security.overrides",
                                        "resources",
                                        "config",
                                        "deployment");

    /// Known table array names in blueprint TOML.
    Set<String> KNOWN_TABLE_ARRAYS = Set.of("slices", "deployment.canary.stages");

    /// Detect unrecognized top-level sections in a blueprint TOML document.
    /// Returns a list of warning messages for each unrecognized section found.
    static List<String> detectUnrecognizedSections(String dsl) {
        var parseResult = option(dsl).filter(s -> !s.isBlank())
                                .toResult(MISSING_ID)
                                .flatMap(content -> TomlParser.parse(content)
        .mapError(cause -> cause("TOML parse error: " + cause.message())));
        return parseResult.fold(_ -> List.of(), BlueprintParser::collectUnrecognizedSections);
    }

    private static List<String> collectUnrecognizedSections(TomlDocument doc) {
        var warnings = new ArrayList<String>();
        doc.sectionNames().stream()
                        .filter(name -> !KNOWN_SECTIONS.contains(name))
                        .filter(name -> !name.startsWith("deployment."))
                        .filter(name -> !name.startsWith("security."))
                        .sorted()
                        .forEach(name -> warnings.add("Unrecognized config section: [" + name + "]"));
        doc.tableArrayNames().stream()
                           .filter(name -> !KNOWN_TABLE_ARRAYS.contains(name))
                           .sorted()
                           .forEach(name -> warnings.add("Unrecognized config table array: [[" + name + "]]"));
        return List.copyOf(warnings);
    }

    private static SecurityOverrides parseSecurityOverrides(TomlDocument doc) {
        var policy = doc.getString("security", "override_policy").map(SecurityOverridePolicy::fromString)
                                  .or(SecurityOverridePolicy.STRENGTHEN_ONLY);
        var overrideMap = doc.getSection("security.overrides");
        if ( overrideMap.isEmpty()) {
        return SecurityOverrides.securityOverrides(List.of(), policy);}
        return SecurityOverrides.fromMap(overrideMap, policy);
    }
}
