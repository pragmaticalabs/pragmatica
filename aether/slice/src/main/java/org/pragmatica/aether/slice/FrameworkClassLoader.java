// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.FileOps.exists;
import static org.pragmatica.lang.io.FileOps.isDirectory;
import static org.pragmatica.lang.io.FileOps.list;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public class FrameworkClassLoader extends URLClassLoader {
    private static final Logger log = LoggerFactory.getLogger(FrameworkClassLoader.class);

    private final List<String> loadedJars = new ArrayList<>();

    public FrameworkClassLoader(URL[] frameworkJars) {
        super(frameworkJars, ClassLoader.getPlatformClassLoader());
        for (URL jar : frameworkJars) {
            loadedJars.add(extractJarName(jar));
        }

        log.info("FrameworkClassLoader created with {} JARs: {}", frameworkJars.length, loadedJars);
    }

    public static Result<FrameworkClassLoader> fromDirectory(Path frameworkDir) {
        if (!isDirectory(frameworkDir)) {
            return cause("Framework directory does not exist: " + frameworkDir).result();
        }

        return list(frameworkDir).mapError(e -> cause("Failed to scan framework directory: " + e.message()))
                   .flatMap(paths -> toFrameworkClassLoader(paths, frameworkDir));
    }

    private static Result<FrameworkClassLoader> toFrameworkClassLoader(java.util.List<Path> paths, Path frameworkDir) {
        var jarUrls = paths.stream().filter(path -> path.toString()
                                                        .endsWith(".jar")).map(FrameworkClassLoader::toUrl).filter(Result::isSuccess).map(Result::unwrap).toArray(URL[]::new);

        if (jarUrls.length == 0) {
            return cause("No JAR files found in framework directory: " + frameworkDir).result();
        }

        return success(new FrameworkClassLoader(jarUrls));
    }

    public static Result<FrameworkClassLoader> fromJars(Path... jarPaths) {
        var urls = new ArrayList<URL>();
        var errors = new ArrayList<String>();

        for (var jarPath : jarPaths) {
            if (!exists(jarPath)) {
                errors.add("JAR not found: " + jarPath);
                continue;
            }

            toUrl(jarPath).onSuccess(urls::add).onFailure(cause -> errors.add(cause.message()));
        }

        if (!errors.isEmpty()) {
            return cause("Failed to load framework JARs: " + String.join(", ", errors)).result();
        }

        return success(new FrameworkClassLoader(urls.toArray(URL[]::new)));
    }

    public List<String> getLoadedJars() {
        return List.copyOf(loadedJars);
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    @Override
    public void close() throws IOException {
        log.info("Closing FrameworkClassLoader with {} JARs", loadedJars.size());
        loadedJars.clear();
        super.close();
    }

    private static Result<URL> toUrl(Path path) {
        return Result.lift(Causes::fromThrowable,
                           () -> path.toUri()
                                     .toURL());
    }

    private static String extractJarName(URL url) {
        var path = url.getPath();
        var lastSlash = path.lastIndexOf('/');

        return lastSlash >= 0
               ? path.substring(lastSlash + 1)
               : path;
    }
}
