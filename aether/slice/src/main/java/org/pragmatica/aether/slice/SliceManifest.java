package org.pragmatica.aether.slice;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URL;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.pragmatica.lang.Option.option;

/// Reads slice artifact metadata from JAR manifest.
///
///
/// Expected manifest attributes:
/// ```
/// Manifest-Version: 1.0
/// Slice-Artifact: org.example:my-slice:1.0.0
/// Slice-Class: org.example.MySlice
/// ```
///
///
/// A valid slice JAR MUST contain these manifest attributes.
/// Loading will fail if manifest is missing or invalid.
@SuppressWarnings({"JBCT-VO-01", "JBCT-SEQ-01", "JBCT-LAM-01", "JBCT-LAM-02", "JBCT-NEST-01", "JBCT-UTIL-02", "JBCT-ZONE-03"})
public interface SliceManifest {
    String SLICE_ARTIFACT_ATTR = "Slice-Artifact";
    String SLICE_CLASS_ATTR = "Slice-Class";

    /// Read slice manifest from a JAR URL.
    ///
    /// @param jarUrl URL pointing to the slice JAR file
    ///
    /// @return SliceManifestInfo or error if manifest is missing/invalid
    static Result<SliceManifestInfo> read(URL jarUrl) {
        return readManifest(jarUrl).flatMap(SliceManifest::parseManifest);
    }

    /// Read slice manifest from a ClassLoader's resources.
    ///
    /// @param classLoader ClassLoader to read manifest from
    ///
    /// @return SliceManifestInfo or error if manifest is missing/invalid
    static Result<SliceManifestInfo> readFromClassLoader(ClassLoader classLoader) {
        return Result.lift(Causes::fromThrowable,
                           () -> classLoader.getResource(JarFile.MANIFEST_NAME))
                     .flatMap(SliceManifest::resolveManifestUrl)
                     .flatMap(SliceManifest::parseManifest);
    }

    private static Result<Manifest> resolveManifestUrl(URL url) {
        return option(url).toResult(MANIFEST_NOT_FOUND)
                     .flatMap(SliceManifest::readManifestFromUrl);
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
        return Result.lift(Causes::fromThrowable,
                           () -> extractManifestFromJar(jarFile))
                     .flatMap(opt -> opt.toResult(MANIFEST_NOT_FOUND_FN.apply(jarUrl.toString())));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Option<Manifest> extractManifestFromJar(JarFile jarFile) throws IOException {
        try (jarFile) {
            return option(jarFile.getManifest());
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
        return option(mainAttrs.getValue(SLICE_ARTIFACT_ATTR)).filter(s -> !s.isBlank())
                     .toResult(MISSING_ARTIFACT_ATTR)
                     .flatMap(artifactStr -> option(mainAttrs.getValue(SLICE_CLASS_ATTR)).filter(s -> !s.isBlank())
                                                   .toResult(MISSING_CLASS_ATTR)
                                                   .flatMap(sliceClass -> Artifact.artifact(artifactStr)
                                                                                  .map(artifact -> new SliceManifestInfo(artifact,
                                                                                                                         sliceClass))));
    }

    /// Information extracted from a slice JAR manifest.
    record SliceManifestInfo(Artifact artifact, String sliceClassName) {}

    // Error causes
    Fn1<Cause, String> MANIFEST_NOT_FOUND_FN = Causes.forOneValue("Manifest not found in JAR: %s");
    Cause MANIFEST_NOT_FOUND = Causes.cause("Manifest not found in ClassLoader resources");
    Cause MISSING_ARTIFACT_ATTR = Causes.cause("Missing required manifest attribute: " + SLICE_ARTIFACT_ATTR
                                               + ". Slice JARs must declare artifact coordinates in manifest.");
    Cause MISSING_CLASS_ATTR = Causes.cause("Missing required manifest attribute: " + SLICE_CLASS_ATTR
                                            + ". Slice JARs must declare the main slice class in manifest.");
}
