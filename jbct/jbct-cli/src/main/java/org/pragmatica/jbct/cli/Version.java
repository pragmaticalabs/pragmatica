package org.pragmatica.jbct.cli;

import java.io.IOException;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Provides version information for JBCT CLI.
///
/// The version string alone does NOT identify a build. Every jar built from a release branch
/// reports the same `1.0.0-rc3`, so an installed jar months old is indistinguishable from one
/// built from the working tree a minute ago — and "I ran `jbct lint`" then proves nothing about
/// the code under test. Issue #620 was filed on exactly that confusion: a fix verified as broken
/// through the CLI was in fact correct, and the findings came from a stale installed jar still
/// first on `PATH`. The build timestamp is what breaks the tie, so `--version` reports it.
public final class Version {
    private static final Logger log = LoggerFactory.getLogger(Version.class);
    private static final String UNKNOWN = "unknown";
    private static final String VERSION;
    private static final String BUILD_TIME;

    static {
        var props = new Properties();

        try (var is = Version.class.getResourceAsStream("/jbct-version.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            log.debug("Failed to load version properties: {}", e.getMessage());
        }

        VERSION = props.getProperty("version", UNKNOWN);
        BUILD_TIME = props.getProperty("buildTime", UNKNOWN);
    }

    private Version() {}

    /// Get the current JBCT version. Identifies the RELEASE, not the build — see [#full()].
    public static String get() {
        return VERSION;
    }

    /// Build timestamp (UTC, ISO-8601), or `unknown` when the stamped resource is absent.
    public static String buildTime() {
        return BUILD_TIME;
    }

    /// Version plus build timestamp — what `--version` prints, and the only form that tells two
    /// builds of the same version apart.
    public static String full() {
        return VERSION + " (built " + BUILD_TIME + ")";
    }
}
