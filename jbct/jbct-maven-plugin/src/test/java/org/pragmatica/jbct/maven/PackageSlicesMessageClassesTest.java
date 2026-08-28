package org.pragmatica.jbct.maven;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import org.pragmatica.jbct.slice.SliceManifest;

import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Slice-jar packaging tests for #712: a topic message record living in a shared package OUTSIDE
/// the slice package (ticketing shape: `shared.event.SeatReleased`) must land in the packaged
/// slice jar, together with its member classes and the component types it references from other
/// `shared.*` subpackages. Drives the same collection walk createImplJar performs
/// (allImplClasses + addSharedCode) against a real JarArchiver and asserts on the built jar.
class PackageSlicesMessageClassesTest {
    private static final String SLICE_PKG_DIR = "org/pragmatica/example/ticketing/sweepholds";
    private static final String SHARED_EVENT_DIR = "org/pragmatica/example/ticketing/shared/event";
    private static final String SHARED_MODEL_DIR = "org/pragmatica/example/ticketing/shared/model";

    private static final String MANIFEST_TEXT = """
        slice.name=SweepHolds
        slice.package=org.pragmatica.example.ticketing.sweepholds
        slice.artifactId=ticketing-sweep-holds
        impl.classes=org.pragmatica.example.ticketing.sweepholds.SweepHolds,org.pragmatica.example.ticketing.sweepholds.SweepHoldsFactory
        request.classes=org.pragmatica.example.ticketing.sweepholds.SweepRequest
        publish.message.classes=org.pragmatica.example.ticketing.shared.event.SeatReleased
        publish.topics.count=1
        publish.topic.0.config=seat-released
        publish.topic.0.messageType=org.pragmatica.example.ticketing.shared.event.SeatReleased
        """;

    @TempDir
    Path tempDir;

    @Test
    void createImplJarClassSet_messageRecordInSharedSubpackage_landsInJarWithMembersAndComponents() throws Exception {
        var classesDir = tempDir.resolve("classes");

        writeClass(classesDir, SLICE_PKG_DIR + "/SweepHolds.class");
        writeClass(classesDir, SLICE_PKG_DIR + "/SweepHoldsFactory.class");
        writeClass(classesDir, SLICE_PKG_DIR + "/SweepRequest.class");
        // Message record + member (nested) class in shared.event — OUTSIDE the slice package
        writeClass(classesDir, SHARED_EVENT_DIR + "/SeatReleased.class");
        writeClass(classesDir, SHARED_EVENT_DIR + "/SeatReleased$SeatRef.class");
        // Component type the message record references, in a different shared subpackage
        writeClass(classesDir, SHARED_MODEL_DIR + "/SeatId.class");

        var manifest = SliceManifest.load(new ByteArrayInputStream(MANIFEST_TEXT.getBytes(StandardCharsets.UTF_8)))
                                    .unwrap();
        var mojo = new PackageSlicesMojo();

        setClassesDirectory(mojo, classesDir.toFile());

        var jarPath = tempDir.resolve("ticketing-sweep-holds.jar");
        var archiver = new JarArchiver();

        archiver.setDestFile(jarPath.toFile());
        // Same collection walk createImplJar performs: manifest class set, then shared code
        for (var className : manifest.allImplClasses()) {
            invokeAddClassFiles(mojo, archiver, className);
        }

        invokeAddSharedCode(mojo, archiver, manifest);
        archiver.createArchive();

        var entries = jarEntries(jarPath);

        assertTrue(entries.contains(SHARED_EVENT_DIR + "/SeatReleased.class"),
                   "manifest-declared publish message class must land in the slice jar, got: " + entries);
        assertTrue(entries.contains(SHARED_EVENT_DIR + "/SeatReleased$SeatRef.class"),
                   "member classes of the message record must land in the slice jar, got: " + entries);
        assertTrue(entries.contains(SHARED_MODEL_DIR + "/SeatId.class"),
                   "component types in other shared subpackages must land in the slice jar, got: " + entries);
        assertTrue(entries.contains(SLICE_PKG_DIR + "/SweepHolds.class"),
                   "slice impl classes must still land in the slice jar, got: " + entries);
    }

    private static void writeClass(Path classesDir, String relativePath) throws Exception {
        var file = classesDir.resolve(relativePath);

        Files.createDirectories(file.getParent());
        // Content is never parsed: no bytecode transformation runs with an empty version map
        Files.write(file, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
    }

    private static Set<String> jarEntries(Path jarPath) throws Exception {
        var entries = new HashSet<String>();

        try (var jar = new JarFile(jarPath.toFile())) {
            jar.entries()
               .asIterator()
               .forEachRemaining(entry -> entries.add(entry.getName()));
        }

        return entries;
    }

    private static void setClassesDirectory(PackageSlicesMojo mojo, File classesDir) throws Exception {
        var field = PackageSlicesMojo.class.getDeclaredField("classesDirectory");

        field.setAccessible(true);
        field.set(mojo, classesDir);
    }

    private static void invokeAddClassFiles(PackageSlicesMojo mojo, JarArchiver archiver, String className) throws Exception {
        var method = PackageSlicesMojo.class.getDeclaredMethod("addClassFiles",
                                                               JarArchiver.class,
                                                               String.class,
                                                               Map.class);

        method.setAccessible(true);
        method.invoke(mojo, archiver, className, Map.of());
    }

    private static void invokeAddSharedCode(PackageSlicesMojo mojo, JarArchiver archiver, SliceManifest manifest) throws Exception {
        var method = PackageSlicesMojo.class.getDeclaredMethod("addSharedCode",
                                                               JarArchiver.class,
                                                               SliceManifest.class);

        method.setAccessible(true);
        method.invoke(mojo, archiver, manifest);
    }
}
