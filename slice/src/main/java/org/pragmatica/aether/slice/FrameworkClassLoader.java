package org.pragmatica.aether.slice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClassLoader for framework classes shared by all slices.
 * <p>
 * This classloader sits between the Platform ClassLoader and SharedLibraryClassLoader,
 * providing isolation between the Node's framework copy and the Slice's framework copy.
 * <p>
 * <b>ClassLoader Hierarchy:</b>
 * <pre>
 * Bootstrap (JDK)
 *     ↑
 * Platform (JDK modules)
 *     ↑
 * FrameworkClassLoader (pragmatica-lite, slice-api, Fury) ← THIS
 *     ↑
 * SharedLibraryClassLoader ([shared] deps)
 *     ↑
 * SliceClassLoader (slice JAR)
 * </pre>
 * <p>
 * By using Platform ClassLoader (not Application ClassLoader) as parent,
 * slices are completely isolated from the Node's classes. This prevents:
 * <ul>
 *   <li>Class version conflicts between Node and Slice</li>
 *   <li>Accidental leakage of Node internals to Slices</li>
 *   <li>Framework serialization issues (Fury/Kryo class identity)</li>
 * </ul>
 * <p>
 * The framework JARs are loaded from a dedicated directory (e.g., lib/framework/).
 * <p>
 * <b>Required JARs:</b>
 * <ul>
 *   <li>pragmatica-lite:core - Result, Promise, Option, etc.</li>
 *   <li>aether:slice-api - Slice, SliceBridge, SliceMethod, etc.</li>
 *   <li>fury-core - Serialization library</li>
 *   <li>slf4j-api - Logging facade</li>
 * </ul>
 *
 * @see SharedLibraryClassLoader
 * @see SliceClassLoader
 */
public class FrameworkClassLoader extends URLClassLoader {
    private static final Logger log = LoggerFactory.getLogger(FrameworkClassLoader.class);

    private final List<String> loadedJars = new ArrayList<>();

    /**
     * Create a FrameworkClassLoader with Platform ClassLoader as parent.
     * <p>
     * Using Platform ClassLoader bypasses the Application ClassLoader,
     * ensuring complete isolation from Node classes.
     *
     * @param frameworkJars URLs to framework JAR files
     */
    public FrameworkClassLoader(URL[] frameworkJars) {
        super(frameworkJars, ClassLoader.getPlatformClassLoader());
        for (URL jar : frameworkJars) {
            loadedJars.add(extractJarName(jar));
        }
        log.info("FrameworkClassLoader created with {} JARs: {}", frameworkJars.length, loadedJars);
    }

    /**
     * Create a FrameworkClassLoader by scanning a directory for JAR files.
     *
     * @param frameworkDir Path to directory containing framework JARs
     * @return Result containing FrameworkClassLoader, or error if directory invalid
     */
    public static Result<FrameworkClassLoader> fromDirectory(Path frameworkDir) {
        if (!Files.isDirectory(frameworkDir)) {
            return Causes.cause("Framework directory does not exist: " + frameworkDir)
                         .result();
        }
        try (Stream<Path> jarStream = Files.list(frameworkDir)) {
            var jarUrls = jarStream.filter(path -> path.toString()
                                                       .endsWith(".jar"))
                                   .map(FrameworkClassLoader::toUrl)
                                   .filter(Result::isSuccess)
                                   .map(Result::unwrap)
                                   .toArray(URL[]::new);
            if (jarUrls.length == 0) {
                return Causes.cause("No JAR files found in framework directory: " + frameworkDir)
                             .result();
            }
            return Result.success(new FrameworkClassLoader(jarUrls));
        } catch (IOException e) {
            return Causes.cause("Failed to scan framework directory: " + e.getMessage())
                         .result();
        }
    }

    /**
     * Create a FrameworkClassLoader from explicit JAR paths.
     *
     * @param jarPaths Paths to framework JAR files
     * @return Result containing FrameworkClassLoader, or error if any JAR invalid
     */
    public static Result<FrameworkClassLoader> fromJars(Path... jarPaths) {
        var urls = new ArrayList<URL>();
        var errors = new ArrayList<String>();
        for (var jarPath : jarPaths) {
            if (!Files.exists(jarPath)) {
                errors.add("JAR not found: " + jarPath);
                continue;
            }
            toUrl(jarPath)
            .onSuccess(urls::add)
            .onFailure(cause -> errors.add(cause.message()));
        }
        if (!errors.isEmpty()) {
            return Causes.cause("Failed to load framework JARs: " + String.join(", ", errors))
                         .result();
        }
        return Result.success(new FrameworkClassLoader(urls.toArray(URL[]::new)));
    }

    /**
     * Get list of JAR names loaded by this classloader.
     *
     * @return List of JAR file names
     */
    public List<String> getLoadedJars() {
        return List.copyOf(loadedJars);
    }

    @Override
    public void close() throws IOException {
        log.info("Closing FrameworkClassLoader with {} JARs", loadedJars.size());
        loadedJars.clear();
        super.close();
    }

    private static Result<URL> toUrl(Path path) {
        return Result.lift(
        Causes::fromThrowable,
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
