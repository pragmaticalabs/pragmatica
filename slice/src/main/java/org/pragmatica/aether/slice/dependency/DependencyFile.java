package org.pragmatica.aether.slice.dependency;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsed dependency file with shared and slice sections.
 * <p>
 * File format:
 * <pre>
 * # Comment line
 *
 * [shared]
 * # Libraries shared across all slices
 * org.pragmatica-lite:core:^0.8.0
 * org.example:order-domain:^1.0.0
 *
 * [slices]
 * # Other slices this slice depends on
 * org.example:inventory-service:^1.0.0
 * org.example:pricing-service:^1.0.0
 * </pre>
 * <p>
 * For backward compatibility, lines without any section header are treated as slice dependencies.
 *
 * @param shared List of shared library dependencies
 * @param slices List of slice dependencies
 */
public record DependencyFile(
        List<ArtifactDependency> shared,
        List<ArtifactDependency> slices
) {

    private enum Section {
        NONE,       // No section yet (for backward compatibility)
        SHARED,     // [shared] section
        SLICES      // [slices] section
    }

    /**
     * Parse dependency file content.
     *
     * @param content The file content as string
     * @return Parsed dependency file or error
     */
    public static Result<DependencyFile> parse(String content) {
        var shared = new ArrayList<ArtifactDependency>();
        var slices = new ArrayList<ArtifactDependency>();
        var currentSection = Section.NONE;

        var lines = content.split("\n");
        for (var line : lines) {
            var trimmed = line.trim();

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            // Check for section headers
            if (trimmed.equals("[shared]")) {
                currentSection = Section.SHARED;
                continue;
            }

            if (trimmed.equals("[slices]")) {
                currentSection = Section.SLICES;
                continue;
            }

            // Unknown section header
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                return UNKNOWN_SECTION.apply(trimmed).result();
            }

            // Parse dependency line - skip empty/comment lines, fail on real errors
            var parseResult = ArtifactDependency.parse(trimmed);

            // Use fold to handle success and failure cases
            final var currentSectionFinal = currentSection;
            var continueFlag = new boolean[]{false};
            var errorResult = new Result[]{null};

            parseResult
                .onSuccess(dependency -> {
                    switch (currentSectionFinal) {
                        case SHARED -> shared.add(dependency);
                        case SLICES, NONE -> slices.add(dependency);
                    }
                })
                .onFailure(cause -> {
                    // Skip known non-error cases
                    if (cause == ArtifactDependency.EMPTY_LINE ||
                        cause == ArtifactDependency.COMMENT_LINE ||
                        cause == ArtifactDependency.SECTION_HEADER) {
                        continueFlag[0] = true;
                    } else {
                        errorResult[0] = cause.result();
                    }
                });

            if (continueFlag[0]) {
                continue;
            }

            if (errorResult[0] != null) {
                return (Result<DependencyFile>) errorResult[0];
            }
        }

        return Result.success(new DependencyFile(List.copyOf(shared), List.copyOf(slices)));
    }

    /**
     * Parse dependency file from input stream.
     *
     * @param inputStream The input stream to read from
     * @return Parsed dependency file or error
     */
    public static Result<DependencyFile> parse(InputStream inputStream) {
        return Result.lift(Causes::fromThrowable, () -> {
            try (var reader = new BufferedReader(new InputStreamReader(inputStream))) {
                var content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                return content.toString();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read dependency file", e);
            }
        }).flatMap(DependencyFile::parse);
    }

    /**
     * Load dependency file from classloader resource.
     *
     * @param sliceClassName Fully qualified class name of the slice
     * @param classLoader    ClassLoader to load resource from
     * @return Parsed dependency file, empty if no file exists
     */
    public static Result<DependencyFile> load(String sliceClassName, ClassLoader classLoader) {
        var resourcePath = "META-INF/dependencies/" + sliceClassName;
        var resource = classLoader.getResourceAsStream(resourcePath);

        if (resource == null) {
            // No dependencies file means no dependencies - this is valid
            return Result.success(new DependencyFile(List.of(), List.of()));
        }

        return parse(resource);
    }

    /**
     * Check if this file has any shared dependencies.
     */
    public boolean hasSharedDependencies() {
        return !shared.isEmpty();
    }

    /**
     * Check if this file has any slice dependencies.
     */
    public boolean hasSliceDependencies() {
        return !slices.isEmpty();
    }

    /**
     * Check if this is an empty dependency file.
     */
    public boolean isEmpty() {
        return shared.isEmpty() && slices.isEmpty();
    }

    // Error constants
    private static final Fn1<Cause, String> UNKNOWN_SECTION =
            Causes.forValue("Unknown section in dependency file: %s. Valid sections: [shared], [slices]");
}
