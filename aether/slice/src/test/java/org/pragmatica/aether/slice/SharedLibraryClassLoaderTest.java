package org.pragmatica.aether.slice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.dependency.ArtifactDependency;
import org.pragmatica.aether.slice.dependency.VersionPattern;
import org.pragmatica.lang.Option;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

class SharedLibraryClassLoaderTest {

    private SharedLibraryClassLoader loader;

    @BeforeEach
    void setUp() {
        loader = new SharedLibraryClassLoader(getClass().getClassLoader());
    }

    @AfterEach
    void tearDown() throws Exception {
        loader.close();
    }

    @Test
    void newly_created_loader_has_no_artifacts() {
        assertThat(loader.getLoadedArtifacts()).isEmpty();
    }

    @Test
    void isLoaded_returns_false_for_unknown_artifact() {
        assertThat(loader.isLoaded("org.example", "unknown")).isFalse();
    }

    @Test
    void addArtifact_registers_artifact_version() throws Exception {
        var version = Version.version("1.0.0").unwrap();
        var dummyUrl = new URL("file:///dummy.jar");

        loader.addArtifact("org.example", "my-lib", version, dummyUrl);

        assertThat(loader.isLoaded("org.example", "my-lib")).isTrue();
        var loadedVersion = loader.getLoadedVersion("org.example", "my-lib");
        assertThat(loadedVersion).isNotEqualTo(Option.none());
        assertThat(loadedVersion.unwrap()).isEqualTo(version);
    }

    @Test
    void addArtifact_ignores_duplicate_with_different_version() throws Exception {
        var version1 = Version.version("1.0.0").unwrap();
        var version2 = Version.version("2.0.0").unwrap();
        var dummyUrl = new URL("file:///dummy.jar");

        loader.addArtifact("org.example", "my-lib", version1, dummyUrl);
        loader.addArtifact("org.example", "my-lib", version2, dummyUrl);

        // First version wins
        assertThat(loader.getLoadedVersion("org.example", "my-lib").unwrap()).isEqualTo(version1);
    }

    @Test
    void checkCompatibility_returns_empty_for_unknown_artifact() {
        var dependency = ArtifactDependency.artifactDependency("org.example:unknown:1.0.0").unwrap();

        var result = loader.checkCompatibility(dependency);

        assertThat(result).isEqualTo(Option.none());
    }

    @Test
    void checkCompatibility_returns_compatible_when_pattern_matches() throws Exception {
        var version = Version.version("1.5.0").unwrap();
        var dummyUrl = new URL("file:///dummy.jar");
        loader.addArtifact("org.example", "my-lib", version, dummyUrl);

        var dependency = ArtifactDependency.artifactDependency("org.example:my-lib:^1.0.0").unwrap();
        var result = loader.checkCompatibility(dependency);

        assertThat(result).isNotEqualTo(Option.none());
        assertThat(result.unwrap().isCompatible()).isTrue();
    }

    @Test
    void checkCompatibility_returns_conflict_when_pattern_does_not_match() throws Exception {
        var version = Version.version("1.0.0").unwrap();
        var dummyUrl = new URL("file:///dummy.jar");
        loader.addArtifact("org.example", "my-lib", version, dummyUrl);

        var dependency = ArtifactDependency.artifactDependency("org.example:my-lib:^2.0.0").unwrap();
        var result = loader.checkCompatibility(dependency);

        assertThat(result).isNotEqualTo(Option.none());
        assertThat(result.unwrap().isConflict()).isTrue();
    }

    @Test
    void checkCompatibility_with_individual_parameters() throws Exception {
        var version = Version.version("1.5.0").unwrap();
        var dummyUrl = new URL("file:///dummy.jar");
        loader.addArtifact("org.example", "my-lib", version, dummyUrl);

        var pattern = VersionPattern.parse("^1.0.0").unwrap();
        var result = loader.checkCompatibility("org.example", "my-lib", pattern);

        assertThat(result).isNotEqualTo(Option.none());
        assertThat(result.unwrap().isCompatible()).isTrue();
    }

    @Test
    void getLoadedArtifacts_returns_all_registered_artifacts() throws Exception {
        var dummyUrl = new URL("file:///dummy.jar");
        loader.addArtifact("org.example", "lib1", Version.version("1.0.0").unwrap(), dummyUrl);
        loader.addArtifact("org.example", "lib2", Version.version("2.0.0").unwrap(), dummyUrl);
        loader.addArtifact("com.other", "lib3", Version.version("3.0.0").unwrap(), dummyUrl);

        var artifacts = loader.getLoadedArtifacts();

        assertThat(artifacts).hasSize(3);
        assertThat(artifacts).containsKey("org.example:lib1");
        assertThat(artifacts).containsKey("org.example:lib2");
        assertThat(artifacts).containsKey("com.other:lib3");
    }

    @Test
    void getLoadedArtifacts_returns_immutable_copy() throws Exception {
        var dummyUrl = new URL("file:///dummy.jar");
        loader.addArtifact("org.example", "lib1", Version.version("1.0.0").unwrap(), dummyUrl);

        var artifacts = loader.getLoadedArtifacts();

        // Modifying the returned map should not affect the loader
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> artifacts.put("new:key", Version.version("1.0.0").unwrap())
        );
    }

    @Test
    void close_clears_loaded_artifacts() throws Exception {
        var dummyUrl = new URL("file:///dummy.jar");
        loader.addArtifact("org.example", "lib1", Version.version("1.0.0").unwrap(), dummyUrl);

        assertThat(loader.getLoadedArtifacts()).isNotEmpty();

        loader.close();

        assertThat(loader.getLoadedArtifacts()).isEmpty();
    }
}
