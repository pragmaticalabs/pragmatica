package org.pragmatica.jbct.cli;

import java.io.IOException;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides version information for JBCT CLI.
 */
public final class Version {
    private static final Logger LOG = LoggerFactory.getLogger(Version.class);
    private static final String VERSION;

    static {
        var props = new Properties();
        try (var is = Version.class.getResourceAsStream("/jbct-version.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            LOG.debug("Failed to load version properties: {}", e.getMessage());
        }
        VERSION = props.getProperty("version", "unknown");
    }

    private Version() {}

    /**
     * Get the current JBCT version.
     */
    public static String get() {
        return VERSION;
    }
}
