package org.pragmatica.jbct.maven;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.pragmatica.jbct.slice.SliceManifest;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #1408: a slice dependency that reaches no `[slices]` entry must refuse the packaging, not vanish.
///
/// The shape is the one the ticket traces: the consumer was compiled without `-Aslice.groupId` /
/// `-Aslice.artifactId`, so the processor fell back to a package-derived coordinate for its sibling
/// provider. `addLocalSliceDependencies` matches only `groupId:artifactId-` and dropped it without a word;
/// the runtime reads only `[slices]` and the consumer died at load with a NoClassDefFoundError.
class PackageSlicesDependencyReconciliationTest {
    private static final String PROVIDER = "org.example.shop.inventory.InventoryService";

    @TempDir
    Path tempDir;

    @Test
    void classifyDependencies_packageDerivedSiblingCoordinate_refusesNamingTheInterfaceAndTheOptions() {
        var mojo = mojoFor(project(List.of()));
        var thrown = assertThrows(InvocationTargetException.class,
                                  () -> classify(mojo, manifestDependingOn(PROVIDER, "org.example.shop:inventory")));
        var cause = assertInstanceOf(MojoExecutionException.class, thrown.getCause());

        assertTrue(cause.getMessage().contains(PROVIDER), cause.getMessage());
        assertTrue(cause.getMessage().contains("org.example.shop:inventory"), cause.getMessage());
        assertTrue(cause.getMessage().contains("-Aslice.groupId=org.example -Aslice.artifactId=shop"), cause.getMessage());
    }

    @Test
    void classifyDependencies_moduleKeyedSiblingCoordinate_isPackaged() {
        // Control: the coordinate the processor emits WITH the two options is accepted.
        var mojo = mojoFor(project(List.of()));

        assertDoesNotThrow(() -> classify(mojo, manifestDependingOn(PROVIDER, "org.example:shop-inventory-service")));
    }

    @Test
    void classifyDependencies_interfaceProvidedByADirectSliceDependency_isPackaged() throws Exception {
        // Control: an external provider is accounted for by its interface, whatever coordinate was emitted.
        var providerJar = providerJar("InventoryService.manifest", "slice.interface=" + PROVIDER + "\n"
                                                                   + "slice.artifactId=inventory-inventory-service\n"
                                                                   + "base.artifact=org.example:inventory\n");
        var mojo = mojoFor(project(List.of(sliceArtifact(providerJar))));

        assertDoesNotThrow(() -> classify(mojo, manifestDependingOn(PROVIDER, "org.example.shop:inventory")));
    }

    private static Object classify(PackageSlicesMojo mojo, SliceManifest manifest) throws Exception {
        var method = PackageSlicesMojo.class.getDeclaredMethod("classifyDependencies", SliceManifest.class);

        method.setAccessible(true);

        return method.invoke(mojo, manifest);
    }

    private static SliceManifest manifestDependingOn(String interfaceName, String coordinate) {
        var text = "slice.name=Checkout\n"
                   + "slice.package=org.example.shop.checkout\n"
                   + "slice.artifactId=shop-checkout\n"
                   + "dependencies.count=1\n"
                   + "dependency.0.interface=" + interfaceName + "\n"
                   + "dependency.0.artifact=" + coordinate + "\n"
                   + "dependency.0.version=UNRESOLVED\n";

        return SliceManifest.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).unwrap();
    }

    private static MavenProject project(List<Artifact> artifacts) {
        var project = new MavenProject();

        project.setGroupId("org.example");
        project.setArtifactId("shop");
        project.setVersion("1.0.0");
        project.setArtifacts(Set.copyOf(artifacts));
        for (var artifact : artifacts) {
            var dependency = new Dependency();

            dependency.setGroupId(artifact.getGroupId());
            dependency.setArtifactId(artifact.getArtifactId());
            project.getModel().addDependency(dependency);
        }

        return project;
    }

    private static PackageSlicesMojo mojoFor(MavenProject project) {
        var mojo = new PackageSlicesMojo();

        try {
            var field = PackageSlicesMojo.class.getDeclaredField("project");

            field.setAccessible(true);
            field.set(mojo, project);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }

        return mojo;
    }

    private Path providerJar(String manifestName, String manifestText) throws Exception {
        var jar = tempDir.resolve("inventory-1.0.0.jar");

        try (var out = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            out.putNextEntry(new JarEntry("META-INF/slice/" + manifestName));
            out.write(manifestText.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        return jar;
    }

    private static Artifact sliceArtifact(Path jar) {
        var artifact = new DefaultArtifact("org.example", "inventory", "1.0.0", "provided", "jar", null,
                                           new DefaultArtifactHandler("jar"));

        artifact.setFile(jar.toFile());

        return artifact;
    }
}
