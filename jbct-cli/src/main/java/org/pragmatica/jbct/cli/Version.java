package org.pragmatica.jbct.cli;

import java.io.IOException;
import java.util.Properties;

/**
 * Provides version information for JBCT CLI.
 */
public final class Version {
    private static final String VERSION;

    static {
        var props = new Properties();
        try (var is = Version.class.getResourceAsStream("/jbct-version.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException _) {}
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
