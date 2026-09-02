// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;


/// Build stamp of the running slice-processor, read once from the filtered
/// `slice-processor-build.properties` resource on the processor's own classpath (#403).
///
/// Purpose: make a stale, locally-installed processor jar diagnosable. The version is emitted
/// as a one-time processing NOTE and stamped into every generated file header, so output from a
/// stale jar is identifiable after the fact instead of silently reintroducing an already-fixed
/// codegen bug (green -> red with no source change).
///
/// Degrades gracefully: if the resource is missing or its placeholders were not filtered
/// (e.g. running from an unpackaged classpath), the value is [#UNKNOWN] and processing never fails.
public final class BuildInfo {
    /// Value reported when the build stamp cannot be resolved.
    public static final String UNKNOWN = "unknown";
    private static final String RESOURCE = "/slice-processor-build.properties";
    private static final Properties STAMP = loadStamp();
    /// Slice-processor Maven version (e.g. `1.0.0-rc2`), or [#UNKNOWN] when unresolved.
    public static final String VERSION = value("version");
    /// UTC build timestamp of the processor jar, or [#UNKNOWN] when unresolved.
    public static final String BUILD_TIMESTAMP = value("buildTimestamp");

    private BuildInfo() {}

    private static String value(String key) {
        var raw = STAMP.getProperty(key, UNKNOWN);
        // An unfiltered classpath leaves the literal ${...} placeholder - treat as unresolved.
        return raw.isBlank() || raw.startsWith("${")
               ? UNKNOWN
               : raw;
    }

    private static Properties loadStamp() {
        var props = new Properties();

        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException _) {
        // Resource unreadable - degrade to unknown, never fail the build.
        }

        return props;
    }
}
