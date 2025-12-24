package org.pragmatica.aether.slice.routing;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.regex.Pattern;

public record RoutingSection(String protocol, Option<Artifact> connector, List<Route> routes) {
    private static final Pattern PROTOCOL_PATTERN = Pattern.compile("^[a-z][a-z0-9+.-]*$");
    private static final Fn1<Cause, String> INVALID_PROTOCOL = Causes.forValue("Invalid protocol name: %s");

    public static Result<RoutingSection> routingSection(String protocol,
                                                        Option<Artifact> connector,
                                                        List<Route> routes) {
        if (!protocol.matches(PROTOCOL_PATTERN.pattern())) {
            return INVALID_PROTOCOL.apply(protocol).result();
        }
        return Result.success(new RoutingSection(protocol, connector, routes));
    }
}
