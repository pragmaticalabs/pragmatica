package org.pragmatica.aether.slice;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URL;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Reads slice artifact metadata from JAR manifest.
 *
 * <p>Expected manifest attributes:
 * <pre>
 * Manifest-Version: 1.0
 * Slice-Artifact: org.example:my-slice:1.0.0
 * Slice-Class: org.example.MySlice
 * </pre>
 *
 * <p>A valid slice JAR MUST contain these manifest attributes.
 * Loading will fail if manifest is missing or invalid.
 */
public interface SliceManifest {
    String SLICE_ARTIFACT_ATTR = "Slice-Artifact";
    String SLICE_CLASS_ATTR = "Slice-Class";

    /**
     * Read slice manifest from a JAR URL.
     *
     * @param jarUrl URL pointing to the slice JAR file
     *
     * @return SliceManifestInfo or error if manifest is missing/invalid
     */
    static Result<SliceManifestInfo> read(URL jarUrl) {
        return readManifest(jarUrl).flatMap(SliceManifest::parseManifest);
    }

    /**
     * Read slice manifest from a ClassLoader's resources.
     *
     * @param classLoader ClassLoader to read manifest from
     *
     * @return SliceManifestInfo or error if manifest is missing/invalid
     */
    static Result<SliceManifestInfo> readFromClassLoader(ClassLoader classLoader) {
        return Result.lift(Causes::fromThrowable,
                           () -> classLoader.getResource(JarFile.MANIFEST_NAME))
                     .flatMap(url -> url == null
                                     ? MANIFEST_NOT_FOUND.result()
                                     : readManifestFromUrl(url))
                     .flatMap(SliceManifest::parseManifest);
    }

    private static Result<Manifest> readManifest(URL jarUrl) {
        var path = jarUrl.getPath();
        if (path.startsWith("file:")) {
            path = path.substring(5);
        }
        // Remove trailing !/ if present (jar: URL format)
        if (path.contains("!")) {
            path = path.substring(0, path.indexOf("!"));
        }
        var jarPath = path;
        return Result.lift(Causes::fromThrowable,
                           () -> new JarFile(jarPath))
                     .flatMap(jarFile -> extractManifest(jarFile, jarUrl));
    }

    private static Result<Manifest> extractManifest(JarFile jarFile, URL jarUrl) {
        try (jarFile) {
            var manifest = jarFile.getManifest();
            return manifest == null
                   ? MANIFEST_NOT_FOUND_FN.apply(jarUrl.toString())
                                          .result()
                   : Result.success(manifest);
        } catch (IOException e) {
            return Causes.fromThrowable(e)
                         .result();
        }
    }

    private static Result<Manifest> readManifestFromUrl(URL manifestUrl) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               try (var is = manifestUrl.openStream()) {
                                   return new Manifest(is);
                               }
                           });
    }

    private static Result<SliceManifestInfo> parseManifest(Manifest manifest) {
        var mainAttrs = manifest.getMainAttributes();
        var artifactStr = mainAttrs.getValue(SLICE_ARTIFACT_ATTR);
        if (artifactStr == null || artifactStr.isBlank()) {
            return MISSING_ARTIFACT_ATTR.result();
        }
        var sliceClass = mainAttrs.getValue(SLICE_CLASS_ATTR);
        if (sliceClass == null || sliceClass.isBlank()) {
            return MISSING_CLASS_ATTR.result();
        }
        return Artifact.artifact(artifactStr)
                       .map(artifact -> new SliceManifestInfo(artifact, sliceClass));
    }

    /**
     * Information extracted from a slice JAR manifest.
     */
    record SliceManifestInfo(Artifact artifact, String sliceClassName) {}

    // Error causes
    Fn1<Cause, String> MANIFEST_NOT_FOUND_FN = Causes.forOneValue("Manifest not found in JAR: %s");
    Cause MANIFEST_NOT_FOUND = Causes.cause("Manifest not found in ClassLoader resources");
    Cause MISSING_ARTIFACT_ATTR = Causes.cause("Missing required manifest attribute: " + SLICE_ARTIFACT_ATTR
                                               + ". Slice JARs must declare artifact coordinates in manifest.");
    Cause MISSING_CLASS_ATTR = Causes.cause("Missing required manifest attribute: " + SLICE_CLASS_ATTR
                                            + ". Slice JARs must declare the main slice class in manifest.");
}
