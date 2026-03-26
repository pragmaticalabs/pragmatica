package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.cli.cluster.ClusterRegistry.ClusterEntry;
import org.pragmatica.aether.cli.cluster.ClusterRegistry.RegistryError;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class ClusterRegistryTest {

    @TempDir
    Path tempDir;

    private Path registryPath;

    @BeforeEach
    void setUp() {
        registryPath = tempDir.resolve("clusters.toml");
    }

    @Nested
    class LoadTests {

        @Test
        void load_createsEmptyRegistry_whenFileMissing() {
            ClusterRegistry.load(registryPath)
                           .onFailure(c -> fail("Expected success but got: " + c.message()))
                           .onSuccess(registry -> {
                               assertTrue(registry.entries().isEmpty());
                               assertTrue(registry.currentContext().isEmpty());
                           });
        }

        @Test
        void load_parsesExistingFile_whenFileExists() throws IOException {
            var toml = """
                       [current]
                       context = "production"

                       [clusters.production]
                       endpoint = "https://prod.example.com:5150"
                       api_key_env = "AETHER_PROD_KEY"

                       [clusters.staging]
                       endpoint = "https://staging.example.com:5150"
                       """;
            Files.writeString(registryPath, toml);

            ClusterRegistry.load(registryPath)
                           .onFailure(c -> fail("Expected success but got: " + c.message()))
                           .onSuccess(registry -> {
                               assertEquals(some("production"), registry.currentContext());
                               assertEquals(2, registry.entries().size());
                           });
        }
    }

    @Nested
    class AddTests {

        @Test
        void add_appearsInList_whenNewCluster() {
            ClusterRegistry.load(registryPath)
                           .onFailure(c -> fail("Expected success but got: " + c.message()))
                           .onSuccess(registry -> {
                               var updated = registry.add("prod", "https://prod:5150", some("PROD_KEY"));
                               assertEquals(1, updated.entries().size());
                               assertEquals("prod", updated.entries().getFirst().name());
                               assertEquals("https://prod:5150", updated.entries().getFirst().endpoint());
                               assertEquals(some("PROD_KEY"), updated.entries().getFirst().apiKeyEnv());
                           });
        }

        @Test
        void add_setsCurrentContext_whenFirstCluster() {
            ClusterRegistry.load(registryPath)
                           .onFailure(c -> fail("Expected success but got: " + c.message()))
                           .onSuccess(registry -> {
                               var updated = registry.add("prod", "https://prod:5150", none());
                               assertEquals(some("prod"), updated.currentContext());
                           });
        }

        @Test
        void add_replacesExisting_whenSameNameExists() {
            ClusterRegistry.load(registryPath)
                           .onFailure(c -> fail("Expected success but got: " + c.message()))
                           .onSuccess(registry -> {
                               var first = registry.add("prod", "https://old:5150", none());
                               var second = first.add("prod", "https://new:5150", some("KEY"));
                               assertEquals(1, second.entries().size());
                               assertEquals("https://new:5150", second.entries().getFirst().endpoint());
                           });
        }
    }

    @Nested
    class UseTests {

        @Test
        void use_changesCurrentContext_whenClusterExists() {
            ClusterRegistry.load(registryPath)
                           .map(registry -> registry.add("prod", "https://prod:5150", none()))
                           .map(registry -> registry.add("staging", "https://staging:5150", none()))
                           .flatMap(registry -> registry.use("staging"))
                           .onFailure(c -> fail("Expected success but got: " + c.message()))
                           .onSuccess(registry -> assertEquals(some("staging"), registry.currentContext()));
        }

        @Test
        void use_failsWithNotFound_whenClusterMissing() {
            ClusterRegistry.load(registryPath)
                           .flatMap(registry -> registry.use("nonexistent"))
                           .onSuccess(r -> fail("Expected failure"))
                           .onFailure(cause -> assertEquals(
                               RegistryError.General.CLUSTER_NOT_FOUND.message(),
                               cause.message()
                           ));
        }
    }

    @Nested
    class RemoveTests {

        @Test
        void remove_removesFromList_whenClusterExists() {
            ClusterRegistry.load(registryPath)
                           .map(registry -> registry.add("prod", "https://prod:5150", none()))
                           .map(registry -> registry.add("staging", "https://staging:5150", none()))
                           .flatMap(registry -> registry.remove("staging"))
                           .onFailure(c -> fail("Expected success but got: " + c.message()))
                           .onSuccess(registry -> {
                               assertEquals(1, registry.entries().size());
                               assertEquals("prod", registry.entries().getFirst().name());
                           });
        }

        @Test
        void remove_clearsCurrentContext_whenRemovingCurrent() {
            ClusterRegistry.load(registryPath)
                           .map(registry -> registry.add("prod", "https://prod:5150", none()))
                           .flatMap(registry -> registry.remove("prod"))
                           .onFailure(c -> fail("Expected success but got: " + c.message()))
                           .onSuccess(registry -> assertTrue(registry.currentContext().isEmpty()));
        }

        @Test
        void remove_failsWithNotFound_whenClusterMissing() {
            ClusterRegistry.load(registryPath)
                           .flatMap(registry -> registry.remove("nonexistent"))
                           .onSuccess(r -> fail("Expected failure"))
                           .onFailure(cause -> assertEquals(
                               RegistryError.General.CLUSTER_NOT_FOUND.message(),
                               cause.message()
                           ));
        }
    }

    @Nested
    class SaveAndLoadRoundTrip {

        @Test
        void saveAndLoad_preservesState_whenRoundTripped() {
            ClusterRegistry.load(registryPath)
                           .map(registry -> registry.add("prod", "https://prod.example.com:5150", some("PROD_KEY")))
                           .map(registry -> registry.add("staging", "https://staging.example.com:5150", none()))
                           .flatMap(registry -> registry.save().map(unit -> registry))
                           .onFailure(c -> fail("Save failed: " + c.message()));

            // Load again and verify
            ClusterRegistry.load(registryPath)
                           .onFailure(c -> fail("Reload failed: " + c.message()))
                           .onSuccess(registry -> {
                               assertEquals(some("prod"), registry.currentContext());
                               assertEquals(2, registry.entries().size());
                               verifyProdEntry(registry);
                               verifyStagingEntry(registry);
                           });
        }

        private void verifyProdEntry(ClusterRegistry registry) {
            var prod = registry.entries().stream()
                               .filter(e -> e.name().equals("prod"))
                               .findFirst()
                               .orElseThrow();
            assertEquals("https://prod.example.com:5150", prod.endpoint());
            assertEquals(some("PROD_KEY"), prod.apiKeyEnv());
        }

        private void verifyStagingEntry(ClusterRegistry registry) {
            var staging = registry.entries().stream()
                                  .filter(e -> e.name().equals("staging"))
                                  .findFirst()
                                  .orElseThrow();
            assertEquals("https://staging.example.com:5150", staging.endpoint());
            assertTrue(staging.apiKeyEnv().isEmpty());
        }
    }
}
