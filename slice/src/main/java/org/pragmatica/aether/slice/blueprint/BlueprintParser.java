package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.routing.Binding;
import org.pragmatica.aether.slice.routing.BindingSource;
import org.pragmatica.aether.slice.routing.Route;
import org.pragmatica.aether.slice.routing.RouteTarget;
import org.pragmatica.aether.slice.routing.RoutingSection;
import org.pragmatica.aether.slice.routing.SliceSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public interface BlueprintParser {
    Fn2<Cause, Integer, String> PARSE_ERROR = Causes.forTwoValues("Line %d: %s");
    Fn1<Cause, String> FILE_ERROR = Causes.forValue("Failed to read file: %s");
    Cause MISSING_HEADER = Causes.cause("Missing blueprint header");
    Fn1<Cause, String> INVALID_SECTION = Causes.forValue("Invalid section header: %s");
    Fn1<Cause, String> INVALID_SLICE_FORMAT = Causes.forValue("Invalid slice format: %s");
    Fn1<Cause, String> INVALID_ROUTE_FORMAT = Causes.forValue("Invalid route format: %s");
    Fn2<Cause, String, String> BINDING_MISMATCH = Causes.forTwoValues("Parameter '%s' not found in pattern: %s");

    Pattern HEADER_PATTERN = Pattern.compile("^#\\s*Blueprint:\\s*(.+)$");
    Pattern SECTION_PATTERN = Pattern.compile("^\\[([^\\]]+)\\]$");
    Pattern SLICE_PATTERN = Pattern.compile("^([^=]+?)(?:\\s*=\\s*(-?\\d+))?\\s*(?:#.*)?$");
    Pattern ROUTE_PATTERN = Pattern.compile("^(.+?)\\s*=>\\s*(.+?)\\s*(?:#.*)?$");
    Pattern PATH_VAR_PATTERN = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    static Result<Blueprint> parse(String dsl) {
        var lines = dsl.split("\n");
        return parseLines(lines);
    }

    static Result<Blueprint> parseFile(Path path) {
        try {
            var content = Files.readString(path);
            return parse(content);
        } catch (IOException e) {
            return FILE_ERROR.apply(e.getMessage()).result();
        }
    }

    private static Result<Blueprint> parseLines(String[] lines) {
        if (lines.length == 0) {
            return MISSING_HEADER.result();
        }

        return parseHeader(lines[0]).flatMap(id -> parseSections(lines, 1).map(sections -> createBlueprint(id,
                                                                                                           sections)));
    }

    private static Result<BlueprintId> parseHeader(String line) {
        var matcher = HEADER_PATTERN.matcher(line.trim());
        if (!matcher.matches()) {
            return MISSING_HEADER.result();
        }
        return BlueprintId.blueprintId(matcher.group(1).trim());
    }

    private static Result<ParsedSections> parseSections(String[] lines, int startLine) {
        var slices = new ArrayList<SliceSpec>();
        var routingSections = new ArrayList<RoutingSection>();
        var currentLineNum = startLine;

        while (currentLineNum < lines.length) {
            var line = lines[currentLineNum].trim();

            if (line.isEmpty() || line.startsWith("#")) {
                currentLineNum++;
                continue;
            }

            var sectionMatch = SECTION_PATTERN.matcher(line);
            if (!sectionMatch.matches()) {
                return PARSE_ERROR.apply(currentLineNum + 1, "Expected section header").result();
            }

            var sectionHeader = sectionMatch.group(1);
            var sectionResult = parseSection(lines, currentLineNum, sectionHeader, slices, routingSections);

            if (sectionResult.isFailure()) {
                return sectionResult.flatMap(__ -> Result.failure(null));
            }

            currentLineNum = sectionResult.unwrap();
        }

        return Result.success(new ParsedSections(slices, routingSections));
    }

    private static Result<Integer> parseSection(String[] lines,
                                                int sectionStart,
                                                String sectionHeader,
                                                List<SliceSpec> slices,
                                                List<RoutingSection> routingSections) {
        if (sectionHeader.equals("slices")) {
            return parseSlicesSection(lines, sectionStart + 1, slices);
        }

        if (sectionHeader.startsWith("routing:")) {
            return parseRoutingSection(lines, sectionStart + 1, sectionHeader, routingSections);
        }

        return INVALID_SECTION.apply(sectionHeader).result();
    }

    private static Result<Integer> parseSlicesSection(String[] lines, int startLine, List<SliceSpec> slices) {
        var lineNum = startLine;

        while (lineNum < lines.length) {
            var line = lines[lineNum].trim();

            if (line.isEmpty() || line.startsWith("#")) {
                lineNum++;
                continue;
            }

            if (line.startsWith("[")) {
                return Result.success(lineNum);
            }

            var sliceResult = parseSliceLine(line, lineNum + 1);
            if (sliceResult.isFailure()) {
                return sliceResult.flatMap(__ -> Result.failure(null));
            }

            slices.add(sliceResult.unwrap());
            lineNum++;
        }

        return Result.success(lineNum);
    }

    private static Result<SliceSpec> parseSliceLine(String line, int lineNum) {
        var matcher = SLICE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return PARSE_ERROR.apply(lineNum, INVALID_SLICE_FORMAT.apply(line).message()).result();
        }

        var artifactStr = matcher.group(1).trim();
        var instancesStr = matcher.group(2);

        return Artifact.artifact(artifactStr).flatMap(artifact -> parseInstanceCount(instancesStr, lineNum).flatMap(
                instances -> SliceSpec.sliceSpec(artifact, instances)));
    }

    private static Result<Integer> parseInstanceCount(String instancesStr, int lineNum) {
        if (instancesStr == null) {
            return Result.success(1);
        }
        return Result.lift(_ -> PARSE_ERROR.apply(lineNum, "Invalid instance count: " + instancesStr),
                           () -> Integer.parseInt(instancesStr));
    }

    private static Result<Integer> parseRoutingSection(String[] lines,
                                                       int startLine,
                                                       String sectionHeader,
                                                       List<RoutingSection> routingSections) {
        var parts = sectionHeader.split(":", 3);
        var protocol = parts[1];
        var connectorResult = parseConnectorArtifact(parts);

        if (connectorResult.isFailure()) {
            return connectorResult.flatMap(__ -> Result.failure(null));
        }

        var connectorOpt = connectorResult.unwrap();

        var routes = new ArrayList<Route>();
        var lineNum = startLine;

        while (lineNum < lines.length) {
            var line = lines[lineNum].trim();

            if (line.isEmpty() || line.startsWith("#")) {
                lineNum++;
                continue;
            }

            if (line.startsWith("[")) {
                var sectionResult = RoutingSection.routingSection(protocol, connectorOpt, routes);
                if (sectionResult.isFailure()) {
                    return sectionResult.flatMap(__ -> Result.failure(null));
                }
                routingSections.add(sectionResult.unwrap());
                return Result.success(lineNum);
            }

            var routeResult = parseRouteLine(line, lineNum + 1, protocol);
            if (routeResult.isFailure()) {
                return routeResult.flatMap(__ -> Result.failure(null));
            }

            routes.add(routeResult.unwrap());
            lineNum++;
        }

        var sectionResult = RoutingSection.routingSection(protocol, connectorOpt, routes);
        if (sectionResult.isFailure()) {
            return sectionResult.flatMap(__ -> Result.failure(null));
        }
        routingSections.add(sectionResult.unwrap());
        return Result.success(lineNum);
    }

    private static Result<Route> parseRouteLine(String line, int lineNum, String protocol) {
        var matcher = ROUTE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return PARSE_ERROR.apply(lineNum, INVALID_ROUTE_FORMAT.apply(line).message()).result();
        }

        var pattern = matcher.group(1).trim();
        var targetStr = matcher.group(2).trim();

        return RouteTarget.routeTarget(targetStr).flatMap(target -> resolveBindings(pattern,
                                                                                    target,
                                                                                    protocol,
                                                                                    lineNum).flatMap(bindings -> Route.route(
                pattern,
                target,
                bindings)));
    }

    private static Result<List<Binding>> resolveBindings(String pattern,
                                                         RouteTarget target,
                                                         String protocol,
                                                         int lineNum) {
        var bindings = new ArrayList<Binding>();

        for (var param : target.params()) {
            var bindingResult = resolveBinding(pattern, param, protocol);
            if (bindingResult.isFailure()) {
                return bindingResult.flatMap(__ -> Result.failure(null));
            }
            bindings.add(bindingResult.unwrap());
        }

        return Result.success(bindings);
    }

    private static Result<Binding> resolveBinding(String pattern, String param, String protocol) {
        if (param.contains(".")) {
            return BindingSource.parse(param).flatMap(source -> Binding.binding(param.replace(".", "_"), source));
        }

        var source = inferBindingSource(pattern, param, protocol);
        return source.flatMap(src -> Binding.binding(param, src));
    }

    private static Result<BindingSource> inferBindingSource(String pattern, String param, String protocol) {
        if (param.equals("body")) {
            return Result.success(new BindingSource.Body());
        }
        if (param.equals("value")) {
            return Result.success(new BindingSource.Value());
        }
        if (param.equals("key")) {
            return Result.success(new BindingSource.Key());
        }

        var pathVarMatcher = PATH_VAR_PATTERN.matcher(pattern);
        while (pathVarMatcher.find()) {
            if (pathVarMatcher.group(1).equals(param)) {
                return isQueryParam(pattern,
                                    param)
                       ? Result.success(new BindingSource.QueryVar(param))
                       : Result.success(new BindingSource.PathVar(
                               param));
            }
        }

        return BINDING_MISMATCH.apply(param, pattern).result();
    }

    private static boolean isQueryParam(String pattern, String param) {
        var queryIndex = pattern.indexOf('?');
        if (queryIndex == -1) {
            return false;
        }
        var paramPattern = "{" + param + "}";
        return pattern.indexOf(paramPattern, queryIndex) != -1;
    }

    private static Blueprint createBlueprint(BlueprintId id, ParsedSections sections) {
        return Blueprint.blueprint(id, sections.slices(), sections.routingSections());
    }

    private static Result<Option<Artifact>> parseConnectorArtifact(String[] parts) {
        if (parts.length != 3) {
            return Result.success(Option.none());
        }
        return Artifact.artifact(parts[2]).map(Option::some);
    }

    record ParsedSections(List<SliceSpec> slices, List<RoutingSection> routingSections) {}
}
