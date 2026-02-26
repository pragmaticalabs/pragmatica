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

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;

/// ClassLoader for framework classes shared by all slices.
///
/// This classloader sits between the Platform ClassLoader and SharedLibraryClassLoader,
/// providing isolation between the Node's framework copy and the Slice's framework copy.
///
/// **ClassLoader Hierarchy:**
/// ```
/// Bootstrap (JDK)
///     ↑
/// Platform (JDK modules)
///     ↑
/// FrameworkClassLoader (pragmatica-lite, slice-api, serialization) ← THIS
///     ↑
/// SharedLibraryClassLoader ([shared] deps)
///     ↑
/// SliceClassLoader (slice JAR)
/// ```
///
/// By using Platform ClassLoader (not Application ClassLoader) as parent,
/// slices are completely isolated from the Node's classes. This prevents:
///
///   - Class version conflicts between Node and Slice
///   - Accidental leakage of Node internals to Slices
///   - Framework serialization issues (class identity across classloaders)
///
///
/// The framework JARs are loaded from a dedicated directory (e.g., lib/framework/).
///
/// **Required JARs:**
///
///   - pragmatica-lite:core - Result, Promise, Option, etc.
///   - aether:slice-api - Slice, SliceBridge, SliceMethod, etc.
///   - serialization-api - Serialization library
///   - slf4j-api - Logging facade
///
///
/// @see SharedLibraryClassLoader
/// @see SliceClassLoader
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public class FrameworkClassLoader extends URLClassLoader {
    private static final Logger log = LoggerFactory.getLogger(FrameworkClassLoader.class);

    private final List<String> loadedJars = new ArrayList<>();

    /// Create a FrameworkClassLoader with Platform ClassLoader as parent.
    ///
    /// Using Platform ClassLoader bypasses the Application ClassLoader,
    /// ensuring complete isolation from Node classes.
    ///
    /// @param frameworkJars URLs to framework JAR files
    public FrameworkClassLoader(URL[] frameworkJars) {
        super(frameworkJars, ClassLoader.getPlatformClassLoader());
        for (URL jar : frameworkJars) {
            loadedJars.add(extractJarName(jar));
        }
        log.info("FrameworkClassLoader created with {} JARs: {}", frameworkJars.length, loadedJars);
    }

    /// Create a FrameworkClassLoader by scanning a directory for JAR files.
    ///
    /// @param frameworkDir Path to directory containing framework JARs
    /// @return Result containing FrameworkClassLoader, or error if directory invalid
    public static Result<FrameworkClassLoader> fromDirectory(Path frameworkDir) {
        if (!Files.isDirectory(frameworkDir)) {
            return cause("Framework directory does not exist: " + frameworkDir).result();
        }
        try (Stream<Path> jarStream = Files.list(frameworkDir)) {
            var jarUrls = jarStream.filter(path -> path.toString()
                                                       .endsWith(".jar"))
                                   .map(FrameworkClassLoader::toUrl)
                                   .filter(Result::isSuccess)
                                   .map(Result::unwrap)
                                   .toArray(URL[]::new);
            if (jarUrls.length == 0) {
                return cause("No JAR files found in framework directory: " + frameworkDir).result();
            }
            return success(new FrameworkClassLoader(jarUrls));
        } catch (IOException e) {
            return cause("Failed to scan framework directory: " + e.getMessage()).result();
        }
    }

    /// Create a FrameworkClassLoader from explicit JAR paths.
    ///
    /// @param jarPaths Paths to framework JAR files
    /// @return Result containing FrameworkClassLoader, or error if any JAR invalid
    public static Result<FrameworkClassLoader> fromJars(Path... jarPaths) {
        var urls = new ArrayList<URL>();
        var errors = new ArrayList<String>();
        for (var jarPath : jarPaths) {
            if (!Files.exists(jarPath)) {
                errors.add("JAR not found: " + jarPath);
                continue;
            }
            toUrl(jarPath).onSuccess(urls::add)
                 .onFailure(cause -> errors.add(cause.message()));
        }
        if (!errors.isEmpty()) {
            return cause("Failed to load framework JARs: " + String.join(", ", errors)).result();
        }
        return success(new FrameworkClassLoader(urls.toArray(URL[]::new)));
    }

    /// Get list of JAR names loaded by this classloader.
    ///
    /// @return List of JAR file names
    public List<String> getLoadedJars() {
        return List.copyOf(loadedJars);
    }

    /// JDK override — kept as void/throws per URLClassLoader contract.
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
