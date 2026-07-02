package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class FixSliceTest {
    @TempDir
    Path tempDir;

    @Nested
    class SliceConfigFixes {
        @Test
        void fix_missingSliceConfig_createsFromTemplate() throws Exception {
            var projectDir = project();
            writeManifest(projectDir, "Greeting", "com.example.greeting", "");
            var sliceConfig = projectDir.resolve("src/main/resources/slices/Greeting.toml");

            var result = FixSlice.fixSlice(projectDir).flatMap(FixSlice::fix);

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(fix -> assertThat(fix.createdFiles()).contains(sliceConfig));
            assertThat(sliceConfig).exists();
            assertThat(Files.readString(sliceConfig))
                      .contains("# Slice configuration for Greeting")
                      .contains("[blueprint]")
                      .contains("instances = 3");
        }

        @Test
        void fix_missingSliceConfig_secondRunIsNoOp() throws Exception {
            var projectDir = project();
            writeManifest(projectDir, "Greeting", "com.example.greeting", "");
            var sliceConfig = projectDir.resolve("src/main/resources/slices/Greeting.toml");

            FixSlice.fixSlice(projectDir).flatMap(FixSlice::fix).onFailure(cause -> fail(cause.message()));
            var afterFirst = Files.readString(sliceConfig);

            var second = FixSlice.fixSlice(projectDir).flatMap(FixSlice::fix);

            second.onFailure(cause -> fail(cause.message()))
                  .onSuccess(fix -> {
                      assertThat(fix.nothingToFix()).isTrue();
                      assertThat(fix.createdFiles()).isEmpty();
                  });
            assertThat(Files.readString(sliceConfig)).isEqualTo(afterFirst);
        }

        @Test
        void fix_existingSliceConfig_neverOverwritten() throws Exception {
            var projectDir = project();
            writeManifest(projectDir, "Greeting", "com.example.greeting", "");
            var sliceConfig = projectDir.resolve("src/main/resources/slices/Greeting.toml");
            Files.createDirectories(sliceConfig.getParent());
            var authored = "[blueprint]\ninstances = 7\n# author-tuned\n";
            Files.writeString(sliceConfig, authored);

            var result = FixSlice.fixSlice(projectDir).flatMap(FixSlice::fix);

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(fix -> assertThat(fix.createdFiles()).doesNotContain(sliceConfig));
            assertThat(Files.readString(sliceConfig))
                      .as("author-owned slice config must be byte-identical")
                      .isEqualTo(authored);
        }
    }

    @Nested
    class ResourceSectionFixes {
        @Test
        void fix_missingSqlSection_appendsDatabaseStub() throws Exception {
            var projectDir = project();
            writeManifest(projectDir, "Orders", "com.example.orders", ref("SqlConnector", "database"));
            presentSliceConfig(projectDir, "Orders");
            var resourcesToml = projectDir.resolve("src/main/resources/resources.toml");
            Files.createDirectories(resourcesToml.getParent());
            Files.writeString(resourcesToml, "[http]\nport = 8070\n");

            var result = FixSlice.fixSlice(projectDir).flatMap(FixSlice::fix);

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(fix -> assertThat(fix.configuredSections()).containsExactly("database"));
            assertThat(Files.readString(resourcesToml))
                      .contains("[http]")
                      .contains("port = 8070")
                      .contains("[database]")
                      .contains("type = \"POSTGRESQL\"")
                      .contains("[database.pool_config]");
        }

        @Test
        void fix_missingUnknownTypeSection_appendsBareTodoStub() throws Exception {
            var projectDir = project();
            writeManifest(projectDir, "Notifier", "com.example.notifier", ref("Publisher", "messaging.orders"));
            presentSliceConfig(projectDir, "Notifier");
            var resourcesToml = projectDir.resolve("src/main/resources/resources.toml");

            var result = FixSlice.fixSlice(projectDir).flatMap(FixSlice::fix);

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(fix -> {
                      assertThat(fix.configuredSections()).containsExactly("messaging.orders");
                      assertThat(fix.createdFiles()).contains(resourcesToml);
                  });
            assertThat(Files.readString(resourcesToml))
                      .contains("[messaging.orders]")
                      .contains("# TODO: configure this Publisher resource")
                      .doesNotContain("type = \"POSTGRESQL\"");
        }

        @Test
        void fix_missingSection_secondRunIsNoOp() throws Exception {
            var projectDir = project();
            writeManifest(projectDir, "Orders", "com.example.orders", ref("SqlConnector", "database"));
            presentSliceConfig(projectDir, "Orders");
            var resourcesToml = projectDir.resolve("src/main/resources/resources.toml");
            Files.createDirectories(resourcesToml.getParent());
            Files.writeString(resourcesToml, "[http]\nport = 8070\n");

            FixSlice.fixSlice(projectDir).flatMap(FixSlice::fix).onFailure(cause -> fail(cause.message()));
            var afterFirst = Files.readString(resourcesToml);

            var second = FixSlice.fixSlice(projectDir).flatMap(FixSlice::fix);

            second.onFailure(cause -> fail(cause.message()))
                  .onSuccess(fix -> assertThat(fix.nothingToFix()).isTrue());
            assertThat(Files.readString(resourcesToml))
                      .as("second run must not change resources.toml")
                      .isEqualTo(afterFirst);
        }

        @Test
        void fix_preExistingSection_leftByteIdentical() throws Exception {
            var projectDir = project();
            writeManifest(projectDir, "Orders", "com.example.orders", ref("SqlConnector", "database"));
            presentSliceConfig(projectDir, "Orders");
            var resourcesToml = projectDir.resolve("src/main/resources/resources.toml");
            Files.createDirectories(resourcesToml.getParent());
            var original = "[database]\ntype = \"POSTGRESQL\"\nname = \"custom\"\n";
            Files.writeString(resourcesToml, original);

            var result = FixSlice.fixSlice(projectDir).flatMap(FixSlice::fix);

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(fix -> assertThat(fix.nothingToFix()).isTrue());
            assertThat(Files.readString(resourcesToml))
                      .as("pre-existing [database] section must be untouched")
                      .isEqualTo(original);
        }
    }

    @Nested
    class Guards {
        @Test
        void fix_nothingMissing_reportsNothingAndWritesNothing() throws Exception {
            var projectDir = project();
            writeManifest(projectDir, "Orders", "com.example.orders", ref("SqlConnector", "database"));
            presentSliceConfig(projectDir, "Orders");
            var resourcesToml = projectDir.resolve("src/main/resources/resources.toml");
            Files.createDirectories(resourcesToml.getParent());
            var original = "[database]\ntype = \"POSTGRESQL\"\n";
            Files.writeString(resourcesToml, original);

            var result = FixSlice.fixSlice(projectDir).flatMap(FixSlice::fix);

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(fix -> {
                      assertThat(fix.nothingToFix()).isTrue();
                      assertThat(fix.createdFiles()).isEmpty();
                      assertThat(fix.configuredSections()).isEmpty();
                  });
            assertThat(Files.readString(resourcesToml)).isEqualTo(original);
        }

        @Test
        void fix_noManifests_failsWithCompileGuidance() throws Exception {
            var projectDir = project();

            var result = FixSlice.fixSlice(projectDir).flatMap(FixSlice::fix);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("mvn compile"))
                  .onSuccess(fix -> fail("Expected failure when no manifests are present"));
        }
    }

    private Path project() throws Exception {
        var projectDir = tempDir.resolve("slice-project");
        Files.createDirectories(projectDir);
        return projectDir;
    }

    private void presentSliceConfig(Path projectDir, String sliceName) throws Exception {
        var sliceConfig = projectDir.resolve("src/main/resources/slices/" + sliceName + ".toml");
        Files.createDirectories(sliceConfig.getParent());
        Files.writeString(sliceConfig, SliceAdder.sliceConfigContent(sliceName));
    }

    private static String ref(String type, String config) {
        return "resources.count=1\nresource.0.type=" + type + "\nresource.0.config=" + config + "\n";
    }

    private void writeManifest(Path projectDir, String sliceName, String slicePackage, String extra) throws Exception {
        var manifestDir = projectDir.resolve("target/classes/META-INF/slice");
        Files.createDirectories(manifestDir);
        var content = "slice.name=" + sliceName + "\n"
                      + "slice.package=" + slicePackage + "\n"
                      + "slice.artifactId=slice-" + sliceName.toLowerCase() + "\n"
                      + extra;
        Files.writeString(manifestDir.resolve(sliceName + ".manifest"), content);
    }
}
