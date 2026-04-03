package org.pragmatica.aether.slice.dependency;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Parsed dependency file with shared, infra, and slice sections.
///
/// File format:
/// ```
/// # Comment line
///
/// [shared]
/// # Libraries shared across all slices
/// org.pragmatica-lite:core:^0.8.0
/// org.example:order-domain:^1.0.0
///
/// [infra]
/// # Infrastructure services
/// org.pragmatica-lite.aether:infra-db-connector:^0.7.0
/// org.pragmatica-lite.aether:infra-database:^0.7.0
///
/// [slices]
/// # Other slices this slice depends on
/// org.example:inventory-service:^1.0.0
/// org.example:pricing-service:^1.0.0
/// ```
///
/// The [infra] section is for infrastructure service dependencies. JARs are loaded
/// into SharedLibraryClassLoader like [shared].
///
/// For backward compatibility, lines without any section header are treated as slice dependencies.
///
/// @param shared List of shared library dependencies
/// @param infra  List of infrastructure service dependencies
/// @param slices List of slice dependencies
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-LAM-01", "JBCT-LAM-02", "JBCT-UTIL-02", "JBCT-PAT-01", "JBCT-RET-05", "JBCT-NAM-01"}) public record DependencyFile(List<ArtifactDependency> shared,
                                                                                                                                                           List<ArtifactDependency> infra,
                                                                                                                                                           List<ArtifactDependency> slices) {
    private enum Section {
        NONE,
        SHARED,
        INFRA,
        SLICES
    }

    public static Result<DependencyFile> dependencyFile(String content) {
        var shared = new ArrayList<ArtifactDependency>();
        var infra = new ArrayList<ArtifactDependency>();
        var slices = new ArrayList<ArtifactDependency>();
        var currentSection = Section.NONE;
        var lines = content.split("\n");
        for (var line : lines) {
            var trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {continue;}
            if (trimmed.equals("[shared]")) {
                currentSection = Section.SHARED;
                continue;
            }
            if (trimmed.equals("[infra]")) {
                currentSection = Section.INFRA;
                continue;
            }
            if (trimmed.equals("[slices]")) {
                currentSection = Section.SLICES;
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {return UNKNOWN_SECTION.apply(trimmed).result();}
            var parseResult = ArtifactDependency.artifactDependency(trimmed);
            final var sectionRef = currentSection;
            var errorHolder = new AtomicReference<Cause>();
            var skipFlag = new AtomicBoolean(false);
            parseResult.onSuccess(dependency -> {
                                      switch (sectionRef){
                case SHARED -> shared.add(dependency);
                case INFRA -> infra.add(dependency);
                case SLICES, NONE -> slices.add(dependency);
            }
                                  })
            .onFailure(cause -> {
                           if (cause == ArtifactDependency.EMPTY_LINE || cause == ArtifactDependency.COMMENT_LINE || cause == ArtifactDependency.SECTION_HEADER) {skipFlag.set(true);} else {errorHolder.set(cause);}
                       });
            if (skipFlag.get()) {continue;}
            if (errorHolder.get() != null) {return errorHolder.get().result();}
        }
        var result = new DependencyFile(List.copyOf(shared), List.copyOf(infra), List.copyOf(slices));
        return result.validateNoFrameworkDependencies();
    }

    private Result<DependencyFile> validateNoFrameworkDependencies() {
        return findFrameworkDependency().fold(() -> success(this),
                                              dep -> FRAMEWORK_DEPENDENCY_ERROR.apply(dep).result());
    }

    private Option<String> findFrameworkDependency() {
        for (var dep : shared) {if (isFrameworkArtifact(dep)) {return some("[shared] " + dep.asString());}}
        for (var dep : infra) {if (isFrameworkArtifact(dep)) {return some("[infra] " + dep.asString());}}
        return none();
    }

    private static boolean isFrameworkArtifact(ArtifactDependency dep) {
        return AETHER_GROUP.equals(dep.groupId()) && FRAMEWORK_ARTIFACTS.contains(dep.artifactId());
    }

    private static final String AETHER_GROUP = "org.pragmatica-lite.aether";

    private static final Set<String> FRAMEWORK_ARTIFACTS = Set.of("slice-api", "infra-api", "slice-annotations");

    private static final Fn1<Cause, String> FRAMEWORK_DEPENDENCY_ERROR = Causes.forOneValue("Slice incorrectly packaged: framework dependency declared in %s. " + "slice-api, infra-api, and slice-annotations are provided by the runtime and must not be declared as dependencies");

    @SuppressWarnings("JBCT-RET-03") public static Result<DependencyFile> dependencyFile(InputStream inputStream) {
        return Result.lift(Causes::fromThrowable,
                           () -> readStreamContent(inputStream))
        .flatMap(DependencyFile::dependencyFile);
    }

    @SuppressWarnings("JBCT-EX-01") private static String readStreamContent(InputStream inputStream) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(inputStream))) {
            var content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {content.append(line).append("\n");}
            return content.toString();
        }
    }

    @SuppressWarnings("JBCT-RET-03") public static Result<DependencyFile> load(String sliceClassName,
                                                                               ClassLoader classLoader) {
        var resourcePath = "META-INF/dependencies/" + sliceClassName;
        var resource = classLoader.getResourceAsStream(resourcePath);
        if (resource == null) {return success(new DependencyFile(List.of(), List.of(), List.of()));}
        return dependencyFile(resource);
    }

    public boolean hasSharedDependencies() {
        return ! shared.isEmpty();
    }

    public boolean hasInfraDependencies() {
        return ! infra.isEmpty();
    }

    public boolean hasSliceDependencies() {
        return ! slices.isEmpty();
    }

    public boolean isEmpty() {
        return shared.isEmpty() && infra.isEmpty() && slices.isEmpty();
    }

    private static final Fn1<Cause, String> UNKNOWN_SECTION = Causes.forOneValue("Unknown section in dependency file: %s. Valid sections: [shared], [infra], [slices]");
}
