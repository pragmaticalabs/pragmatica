package org.pragmatica.jbct.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/// #1455: a `jbct.toml` that cannot be used (syntax error, wrong-typed key) fails the goal with a
/// message naming the file and the key or line, instead of building with the layer skipped.
class ConfigRefusalTest {
    private static final String USER_HOME = "user.home";

    @TempDir
    Path tempDir;

    private String savedUserHome;

    @BeforeEach
    void redirectUserHome() {
        savedUserHome = System.getProperty(USER_HOME);
        System.setProperty(USER_HOME, tempDir.resolve("home").toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty(USER_HOME, savedUserHome);
    }

    private static final class Probe extends AbstractJbctMojo {
        @Override
        public void execute() {}
    }

    private Probe probeFor(Path projectDir) {
        var probe = new Probe();
        var mavenProject = new MavenProject();

        mavenProject.setFile(new File(projectDir.toFile(), "pom.xml"));
        probe.project = mavenProject;

        return probe;
    }

    private Path project(String jbctToml) throws Exception {
        var dir = Files.createDirectories(tempDir.resolve("project"));

        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve("jbct.toml"), jbctToml);

        return dir;
    }

    @Test
    void loadConfig_failsNamingFileKeyAndValue_whenKeyHasWrongType() throws Exception {
        var dir = project("[format]\nmaxLineLength = \"wide\"\n");

        assertThatThrownBy(() -> probeFor(dir).loadConfig()).isInstanceOf(MojoExecutionException.class)
                                                            .hasMessageContaining(dir.resolve("jbct.toml").toString())
                                                            .hasMessageContaining("format.maxLineLength")
                                                            .hasMessageContaining("wide");
    }

    @Test
    void loadConfig_failsNamingFile_whenSyntaxIsInvalid() throws Exception {
        var dir = project("[format\nmaxLineLength = 100\n");

        assertThatThrownBy(() -> probeFor(dir).loadConfig()).isInstanceOf(MojoExecutionException.class)
                                                            .hasMessageContaining(dir.resolve("jbct.toml").toString());
    }

    @Test
    void loadConfig_loads_whenLayerIsWellTyped() throws Exception {
        var dir = project("[format]\nmaxLineLength = 100\n");

        assertThat(probeFor(dir).loadConfig().formatter().maxLineLength()).isEqualTo(100);
    }

    @Test
    void loadBlueprintConfig_failsNamingFileAndKey_whenKeyHasWrongType() throws Exception {
        var dir = project("[format]\nindentSize = \"four\"\n");

        assertThatThrownBy(() -> GenerateBlueprintMojo.loadBlueprintConfig(dir)).isInstanceOf(MojoExecutionException.class)
                                                                                .hasMessageContaining(dir.resolve("jbct.toml").toString())
                                                                                .hasMessageContaining("format.indentSize");
    }

    @Test
    void loadConfig_messageHasNoCompositeWrapper() throws Exception {
        var dir = project("[format]\nmaxLineLength = \"wide\"\n");

        assertThatThrownBy(() -> probeFor(dir).loadConfig()).hasMessageNotContaining("Composite");
    }

    /// A slice config the loader refuses fails the goal naming the file and the key, instead of warning and building the
    /// blueprint with the default instance count.
    @Test
    void refusingSliceConfig_failsNamingSliceFileAndKey_andLoadsAWellTypedOne() throws Exception {
        var bad = Files.writeString(tempDir.resolve("bad.toml"), "[blueprint]\ninstances = \"3x\"\n");
        var good = Files.writeString(tempDir.resolve("good.toml"), "[blueprint]\ninstances = 5\n");

        assertThatThrownBy(() -> GenerateBlueprintMojo.refusingSliceConfig("Hello", bad)).isInstanceOf(MojoExecutionException.class)
                                                                                         .hasMessageContaining("Hello")
                                                                                         .hasMessageContaining(bad.toString())
                                                                                         .hasMessageContaining("blueprint.instances")
                                                                                         .hasMessageContaining("3x");
        assertThat(GenerateBlueprintMojo.refusingSliceConfig("Hello", good)).isNotNull();
    }

    /// The goal's own wiring (not just the helper): the slice named by the manifest has its config read from the classes
    /// directory and a refused one fails the goal.
    @Test
    void loadSliceConfig_failsTheGoal_whenTheSliceConfigIsRefused_andUsesDefaultsWhenAbsent() throws Exception {
        var classes = Files.createDirectories(tempDir.resolve("classes"));
        var mojo = new GenerateBlueprintMojo();
        var field = GenerateBlueprintMojo.class.getDeclaredField("classesDirectory");

        field.setAccessible(true);
        field.set(mojo, classes.toFile());
        Files.writeString(classes.resolve("bad.toml"), "[blueprint]\ninstances = \"3x\"\n");
        Files.writeString(classes.resolve("good.toml"), "[blueprint]\ninstances = 5\n");

        assertThatThrownBy(() -> mojo.loadSliceConfig(manifest("Hello", "bad.toml"))).isInstanceOf(MojoExecutionException.class)
                                                                                    .hasMessageContaining("Hello")
                                                                                    .hasMessageContaining("blueprint.instances")
                                                                                    .hasMessageContaining("3x");
        assertThat(mojo.loadSliceConfig(manifest("Hello", "good.toml"))).isNotNull();
        assertThat(mojo.loadSliceConfig(manifest("Hello", "absent.toml"))).isEqualTo(org.pragmatica.jbct.slice.SliceConfig.defaultConfig());
    }

    private static org.pragmatica.jbct.slice.SliceManifest manifest(String sliceName, String configFile) {
        return new org.pragmatica.jbct.slice.SliceManifest(sliceName, "", "demo", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                                                           java.util.List.of(), "demo:base", "base", java.util.List.of(), configFile, java.util.List.of());
    }

    @Test
    void loadBlueprintConfig_loads_whenLayerIsWellTyped() throws Exception {
        var dir = project("[format]\nmaxLineLength = 100\n");

        assertThat(GenerateBlueprintMojo.loadBlueprintConfig(dir)).isNotNull();
    }
}
