package org.pragmatica.aether.api;

import java.io.IOException;
import java.util.Properties;

/// Build metadata loaded from Maven-filtered properties file at startup.
public record BuildInfo(String buildTimestamp, String buildVersion) {
    private static final BuildInfo INSTANCE = loadBuildInfo();

    public static BuildInfo buildInfo() {
        return INSTANCE;
    }

    private static BuildInfo loadBuildInfo() {
        var props = new Properties();

        try (var is = BuildInfo.class.getClassLoader().getResourceAsStream("build-info.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException ignored) {
            // Fall through to defaults
        }

        return new BuildInfo(
            props.getProperty("build.timestamp", "unknown"),
            props.getProperty("build.version", "unknown")
        );
    }
}
