package org.pragmatica.aether.api;

import org.pragmatica.lang.io.StreamOps;

import java.util.Properties;


/// Build metadata loaded from Maven-filtered properties file at startup.
public record BuildInfo(String buildTimestamp, String buildVersion) {
    private static final BuildInfo INSTANCE = loadBuildInfo();

    public static BuildInfo buildInfo() {
        return INSTANCE;
    }

    private static BuildInfo loadBuildInfo() {
        var props = new Properties();
        StreamOps.openResource(BuildInfo.class.getClassLoader(), "build-info.properties")
                 .onSuccess(is -> { try { props.load(is); } catch (Exception ignored) {} });
        return new BuildInfo(props.getProperty("build.timestamp", "unknown"),
                             props.getProperty("build.version", "unknown"));
    }
}
