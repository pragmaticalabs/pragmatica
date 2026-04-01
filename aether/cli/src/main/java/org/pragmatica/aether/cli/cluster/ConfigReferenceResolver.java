package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Resolves ${env:xxx} and ${secrets:xxx} references in raw TOML configuration strings.
///
/// Resolution happens at the string level BEFORE TOML parsing, so any value
/// in the config can use references -- no per-field special cases.
///
/// Supported formats:
/// - ${env:VARIABLE_NAME} -> direct environment variable lookup
/// - ${secrets:secret-name} -> AETHER_SECRET_NAME env var (uppercased, hyphens to underscores)
///
/// Security:
/// - Only resolves known patterns (${env:} and ${secrets:})
/// - Unknown patterns are left as-is (not an error)
/// - Missing env vars produce clear error with the reference name
/// - Resolved values are never logged
sealed interface ConfigReferenceResolver {
    record unused() implements ConfigReferenceResolver{}

    Pattern REFERENCE_PATTERN = Pattern.compile("\\$\\{(env|secrets):([^}]+)}");

    /// Resolve all references in a raw TOML string.
    /// Returns the TOML string with all references replaced by their values.
    /// If ANY reference cannot be resolved, returns failure listing ALL unresolved references.
    static Result<String> resolveAll(String tomlContent) {
        var matcher = REFERENCE_PATTERN.matcher(tomlContent);
        var unresolved = new LinkedHashSet<String>();
        var resolved = new ArrayList<ResolvedMatch>();
        collectMatches(matcher, unresolved, resolved);
        if ( !unresolved.isEmpty()) {
        return new ClusterConfigError.SecretResolutionFailed(formatUnresolved(unresolved)).result();}
        return success(applyReplacements(tomlContent, resolved));
    }

    private static void collectMatches(Matcher matcher,
                                       LinkedHashSet<String> unresolved,
                                       ArrayList<ResolvedMatch> resolved) {
        while ( matcher.find()) {
            var type = matcher.group(1);
            var name = matcher.group(2);
            var fullRef = matcher.group(0);
            var envVarName = resolveEnvVarName(type, name);
            option(System.getenv(envVarName)).onPresent(value -> resolved.add(new ResolvedMatch(fullRef, value)))
                  .onEmpty(() -> unresolved.add(fullRef + " -> env var " + envVarName + " not set"));
        }
    }

    private static String resolveEnvVarName(String type, String name) {
        return "secrets".equals(type)
               ? "AETHER_" + name.toUpperCase().replace('-', '_')
               : name;
    }

    private static String formatUnresolved(LinkedHashSet<String> unresolved) {
        return String.join("; ", unresolved);
    }

    private static String applyReplacements(String content, ArrayList<ResolvedMatch> resolved) {
        var result = content;
        for ( var match : resolved) {
        result = result.replace(match.reference(), match.value());}
        return result;
    }

    /// A resolved reference match with its replacement value.
    record ResolvedMatch(String reference, String value){}
}
