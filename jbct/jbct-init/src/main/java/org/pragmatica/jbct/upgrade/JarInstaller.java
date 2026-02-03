package org.pragmatica.jbct.upgrade;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.jbct.shared.HttpClients;
import org.pragmatica.jbct.shared.UrlValidation;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads and installs JBCT JAR files.
 */
public final class JarInstaller {
    private static final Logger LOG = LoggerFactory.getLogger(JarInstaller.class);
    private static final String DEFAULT_INSTALL_DIR = ".jbct";
    private static final String LIB_DIR = "lib";
    private static final String JAR_NAME = "jbct.jar";

    private final HttpOperations http;

    private JarInstaller(HttpOperations http) {
        this.http = http;
    }

    /**
     * Create installer with default settings.
     */
    public static JarInstaller jarInstaller() {
        return new JarInstaller(HttpClients.httpOperations());
    }

    /**
     * Get the default installation path (~/.jbct/lib/jbct.jar).
     */
    public static Path defaultInstallPath() {
        return Option.option(System.getProperty("user.home"))
                     .map(userHome -> Path.of(userHome, DEFAULT_INSTALL_DIR, LIB_DIR, JAR_NAME))
                     .or(() -> Path.of(System.getProperty("java.io.tmpdir"), DEFAULT_INSTALL_DIR, LIB_DIR, JAR_NAME));
    }

    /**
     * Auto-detect the current JAR location.
     * Returns the path to the running JAR, or default install path if detection fails.
     */
    public static Path detectCurrentJar() {
        try{
            // Try to get the JAR from class path
            var classPath = System.getProperty("java.class.path");
            if (classPath != null && classPath.endsWith(".jar")) {
                var jarPath = Path.of(classPath)
                                  .toAbsolutePath();
                if (Files.exists(jarPath)) {
                    return jarPath;
                }
            }
            // Try to get from protection domain
            var location = JarInstaller.class.getProtectionDomain()
                                       .getCodeSource()
                                       .getLocation();
            if (location != null) {
                var jarPath = Path.of(location.toURI())
                                  .toAbsolutePath();
                if (Files.isRegularFile(jarPath) && jarPath.toString()
                                                           .endsWith(".jar")) {
                    return jarPath;
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not detect current JAR location: {}", e.getMessage());
        }
        return defaultInstallPath();
    }

    /**
     * Download and install a JAR from URL.
     *
     * @param downloadUrl URL to download from
     * @param targetPath  Path to install to
     * @return Success with installed path, or failure with error
     */
    public Result<Path> install(String downloadUrl, Path targetPath) {
        return download(downloadUrl).flatMap(tempFile -> installFromTemp(tempFile, targetPath));
    }

    /**
     * Download JAR to a temporary file.
     */
    public Result<Path> download(String downloadUrl) {
        return UrlValidation.validateDownloadUrl(downloadUrl)
                            .flatMap(this::downloadFromUri);
    }

    private Result<Path> downloadFromUri(URI uri) {
        try{
            var tempFile = Files.createTempFile("jbct-download-", ".jar");
            var request = HttpRequest.newBuilder()
                                     .uri(uri)
                                     .header("User-Agent", "jbct-cli")
                                     .timeout(Duration.ofMinutes(5))
                                     .GET()
                                     .build();
            return http.send(request,
                             HttpResponse.BodyHandlers.ofFile(tempFile))
                       .await()
                       .flatMap(response -> {
                                    if (response.isSuccess()) {
                                        return Result.success(response.body());
                                    } else {
                                        // Best-effort cleanup of temp file on failure
            try{
                                            Files.deleteIfExists(tempFile);
                                        } catch (IOException cleanupError) {
                                            LOG.debug("Failed to cleanup temp file {}: {}",
                                                      tempFile,
                                                      cleanupError.getMessage());
                                        }
                                        return response.toResult();
                                    }
                                });
        } catch (Exception e) {
            return Causes.cause("Download failed: " + e.getMessage())
                         .result();
        }
    }

    /**
     * Install from a temporary file to target path.
     * Creates parent directories if needed.
     * Uses atomic move when possible.
     */
    public Result<Path> installFromTemp(Path tempFile, Path targetPath) {
        try{
            // Create parent directories
            var parent = targetPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            // Backup existing file
            var backup = backupExisting(targetPath);
            try{
                // Try atomic move first
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Fall back to copy + delete
                Files.copy(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(tempFile);
            }
            // Remove backup on success - best effort cleanup
            backup.onPresent(backupPath -> {
                                 try{
                                     Files.deleteIfExists(backupPath);
                                 } catch (IOException cleanupError) {
                                     LOG.debug("Failed to cleanup backup file {}: {}",
                                               backupPath,
                                               cleanupError.getMessage());
                                 }
                             });
            return Result.success(targetPath);
        } catch (Exception e) {
            return Causes.cause("Installation failed: " + e.getMessage())
                         .result();
        }
    }

    private Option<Path> backupExisting(Path targetPath) {
        if (!Files.exists(targetPath)) {
            return Option.none();
        }
        try{
            var backupPath = targetPath.resolveSibling(targetPath.getFileName() + ".bak");
            Files.copy(targetPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            return Option.option(backupPath);
        } catch (IOException e) {
            return Option.none();
        }
    }

    /**
     * Create initial installation directory structure.
     *
     * @return Path to the installation directory
     */
    public static Result<Path> createInstallDir() {
        try{
            var installDir = defaultInstallPath().getParent()
                                               .getParent();
            // ~/.jbct
            var binDir = installDir.resolve("bin");
            var libDir = installDir.resolve("lib");
            Files.createDirectories(binDir);
            Files.createDirectories(libDir);
            return Result.success(installDir);
        } catch (Exception e) {
            return Causes.cause("Failed to create install directory: " + e.getMessage())
                         .result();
        }
    }

    /**
     * Copy shell wrapper scripts to installation directory.
     * Scripts are loaded from classpath resources.
     */
    public static Result<Path> installWrapperScripts(Path installDir) {
        var binDir = installDir.resolve("bin");
        return copyResource("/dist/bin/jbct",
                            binDir.resolve("jbct"),
                            true).flatMap(_ -> copyResource("/dist/bin/jbct.bat",
                                                            binDir.resolve("jbct.bat"),
                                                            false))
                           .map(_ -> installDir);
    }

    private static Result<Path> copyResource(String resourcePath, Path targetPath, boolean executable) {
        try (var in = JarInstaller.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Causes.cause("Resource not found: " + resourcePath)
                             .result();
            }
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            if (executable) {
                targetPath.toFile()
                          .setExecutable(true);
            }
            return Result.success(targetPath);
        } catch (Exception e) {
            return Causes.cause("Failed to copy " + resourcePath + ": " + e.getMessage())
                         .result();
        }
    }
}
