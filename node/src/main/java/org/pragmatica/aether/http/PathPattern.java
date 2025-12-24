package org.pragmatica.aether.http;

import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiled path pattern for efficient matching.
 * Supports path variables like {id}, {userId}, etc.
 */
public record PathPattern(
        HttpMethod method,
        String originalPattern,
        Pattern regex,
        List<String> variableNames
) {
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{([^}]+)}");

    public static PathPattern compile(String routePattern) {
        // Parse "GET:/api/users/{userId}" format
        var colonIndex = routePattern.indexOf(':');
        if (colonIndex == -1) {
            throw new IllegalArgumentException("Route pattern must include method: " + routePattern);
        }

        var methodStr = routePattern.substring(0, colonIndex);
        var pathPattern = routePattern.substring(colonIndex + 1);

        var method = HttpMethod.fromString(methodStr);
        var variableNames = new ArrayList<String>();

        // Convert path pattern to regex
        var regexBuilder = new StringBuilder("^");
        var matcher = VARIABLE_PATTERN.matcher(pathPattern);
        var lastEnd = 0;

        while (matcher.find()) {
            // Escape literal part
            regexBuilder.append(Pattern.quote(pathPattern.substring(lastEnd, matcher.start())));
            // Add capturing group for variable
            regexBuilder.append("([^/]+)");
            variableNames.add(matcher.group(1));
            lastEnd = matcher.end();
        }

        // Add remaining literal part
        regexBuilder.append(Pattern.quote(pathPattern.substring(lastEnd)));
        regexBuilder.append("$");

        return new PathPattern(method, routePattern, Pattern.compile(regexBuilder.toString()), variableNames);
    }

    public Option<Map<String, String>> match(HttpMethod requestMethod, String path) {
        if (requestMethod != method) {
            return Option.none();
        }

        Matcher matcher = regex.matcher(path);
        if (!matcher.matches()) {
            return Option.none();
        }

        var variables = new HashMap<String, String>();
        for (int i = 0; i < variableNames.size(); i++) {
            variables.put(variableNames.get(i), matcher.group(i + 1));
        }

        return Option.some(variables);
    }
}
