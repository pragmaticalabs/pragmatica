// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;

import java.io.File;
import java.net.URL;
import java.util.jar.JarFile;
import java.util.jar.Manifest;


public final class BuildInfo {
    private static final String DEV_VERSION = "dev";
    private static final String UNKNOWN_DATE = "unknown";
    private static final String VERSION_KEY = "Implementation-Version";
    private static final String BUILD_DATE_KEY = "Implementation-Build-Date";
    private static final BuildInfo CURRENT = load();

    private final String version;
    private final String buildDate;

    private BuildInfo(String version, String buildDate) {
        this.version = version;
        this.buildDate = buildDate;
    }

    public static BuildInfo current() {
        return CURRENT;
    }

    public static String version() {
        return CURRENT.version;
    }

    public static String buildDate() {
        return CURRENT.buildDate;
    }

    public String displayString() {
        return version + " (built " + buildDate + ")";
    }

    private static BuildInfo load() {
        return Option.option(BuildInfo.class.getProtectionDomain().getCodeSource())
                     .map(java.security.CodeSource::getLocation)
                     .flatMap(BuildInfo::toJarFile)
                     .flatMap(BuildInfo::readManifest)
                     .map(BuildInfo::fromManifest)
                     .or(BuildInfo::fallback);
    }

    private static Option<File> toJarFile(URL url) {
        try {
            var file = new File(url.toURI());

            return file.isFile()
                   ? Option.some(file)
                   : Option.empty();
        } catch (Exception _) {
            return Option.empty();
        }
    }

    private static Option<Manifest> readManifest(File jarFile) {
        try (var jar = new JarFile(jarFile)) {
            return Option.option(jar.getManifest());
        } catch (Exception _) {
            return Option.empty();
        }
    }

    private static BuildInfo fromManifest(Manifest manifest) {
        var attrs = manifest.getMainAttributes();
        var version = Option.option(attrs.getValue(VERSION_KEY)).or(DEV_VERSION);
        var date = Option.option(attrs.getValue(BUILD_DATE_KEY)).or(UNKNOWN_DATE);

        return new BuildInfo(version, date);
    }

    private static BuildInfo fallback() {
        return new BuildInfo(DEV_VERSION, UNKNOWN_DATE);
    }
}
