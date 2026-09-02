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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A slice bundle must be closed under bytecode reference, and closed under nothing more.
///
/// The two directions are tested together on purpose: "include everything referenced" and "include
/// everything" both satisfy the positive assertions, and only the negative ones tell them apart.
///
/// The slice package here is a four-level telescope (`booking.purchase.buyticket` under the app
/// root), the shape that defeated the previous sibling-`shared` guess: dropping one segment off the
/// slice package named a directory that does not exist, so nothing shared was bundled at all.
class PackageSlicesReferenceClosureTest {
    private static final String SLICE_DIR = "org/example/app/booking/purchase/buyticket";
    private static final String SHARED_DIR = "org/example/app/shared";
    private static final String OTHER_SLICE_DIR = "org/example/app/pricing/quoting/quoteprice";

    private static final String MANIFEST_TEXT = """
        slice.name=BuyTicket
        slice.package=org.example.app.booking.purchase.buyticket
        slice.artifactId=app-buy-ticket
        impl.classes=org.example.app.booking.purchase.buyticket.BuyTicket,org.example.app.booking.purchase.buyticket.BuyTicketFactory
        request.classes=org.example.app.booking.purchase.buyticket.BuyRequest
        """;

    private static final String FLAT_MANIFEST_TEXT = """
        slice.name=Order
        slice.package=org.example.flat.order
        slice.artifactId=flat-order
        impl.classes=org.example.flat.order.Order,org.example.flat.order.OrderFactory
        """;

    @TempDir
    Path tempDir;

    @Test
    void createImplJarClassSet_classReferencedButNotDeclared_isBundled() throws Exception {
        var classesDir = tempDir.resolve("classes");

        writeClass(classesDir, SLICE_DIR + "/BuyTicket.class", SHARED_DIR + "/Money");
        writeClass(classesDir, SLICE_DIR + "/BuyTicketFactory.class", SLICE_DIR + "/BuyTicket");
        writeClass(classesDir, SLICE_DIR + "/BuyRequest.class", SHARED_DIR + "/SeatId");
        // Referenced from the slice, declared nowhere in the manifest
        writeClass(classesDir, SHARED_DIR + "/Money.class", SHARED_DIR + "/Currency");
        writeClass(classesDir, SHARED_DIR + "/SeatId.class");
        // Second hop: reachable only through Money — proves the walk runs to a fixpoint
        writeClass(classesDir, SHARED_DIR + "/Currency.class");
        // Nested class of a closure-pulled class, referenced by nobody directly
        writeClass(classesDir, SHARED_DIR + "/Money$Rounding.class");

        var entries = buildSliceJar(MANIFEST_TEXT, classesDir, "buy-ticket.jar");

        assertTrue(entries.contains(SHARED_DIR + "/Money.class"),
                   "a class referenced from slice bytecode must be bundled, got: " + entries);
        assertTrue(entries.contains(SHARED_DIR + "/SeatId.class"),
                   "a class referenced from a request type must be bundled, got: " + entries);
        assertTrue(entries.contains(SHARED_DIR + "/Currency.class"),
                   "a transitively referenced class must be bundled, got: " + entries);
        assertTrue(entries.contains(SHARED_DIR + "/Money$Rounding.class"),
                   "nested classes of a pulled-in class must be bundled, got: " + entries);
        assertTrue(entries.contains(SLICE_DIR + "/BuyTicket.class"),
                   "declared impl classes must still be bundled, got: " + entries);
    }

    @Test
    void createImplJarClassSet_classReferencedByNobody_isNotBundled() throws Exception {
        var classesDir = tempDir.resolve("classes");

        writeClass(classesDir, SLICE_DIR + "/BuyTicket.class", SHARED_DIR + "/Money");
        writeClass(classesDir, SLICE_DIR + "/BuyTicketFactory.class", SLICE_DIR + "/BuyTicket");
        writeClass(classesDir, SLICE_DIR + "/BuyRequest.class");
        writeClass(classesDir, SHARED_DIR + "/Money.class");
        // Compiled into the same module, reachable from nothing in this slice
        writeClass(classesDir, SHARED_DIR + "/Unreferenced.class", SHARED_DIR + "/Money");
        writeClass(classesDir, SHARED_DIR + "/Unreferenced$Nested.class");
        writeClass(classesDir, OTHER_SLICE_DIR + "/QuotePrice.class", SHARED_DIR + "/Money");

        var entries = buildSliceJar(MANIFEST_TEXT, classesDir, "buy-ticket-negative.jar");

        assertTrue(entries.contains(SHARED_DIR + "/Money.class"),
                   "referenced class must still be bundled, got: " + entries);
        assertFalse(entries.contains(SHARED_DIR + "/Unreferenced.class"),
                    "a class nothing in the bundle references must NOT be bundled, got: " + entries);
        assertFalse(entries.contains(SHARED_DIR + "/Unreferenced$Nested.class"),
                    "nested classes of an unreferenced class must NOT be bundled, got: " + entries);
        assertFalse(entries.contains(OTHER_SLICE_DIR + "/QuotePrice.class"),
                    "another slice's classes must NOT be bundled, got: " + entries);
        // References run one way: pulling Money in must not drag its referrers along
        assertTrue(entries.contains(SLICE_DIR + "/BuyRequest.class"),
                   "declared request classes must still be bundled, got: " + entries);
    }

    @Test
    void createImplJarClassSet_unreferencedSiblingSharedClass_isNotBundled() throws Exception {
        var classesDir = tempDir.resolve("classes");

        writeClass(classesDir, "org/example/flat/order/Order.class", "org/example/flat/shared/Money");
        writeClass(classesDir, "org/example/flat/order/OrderFactory.class");
        writeClass(classesDir, "org/example/flat/shared/Money.class");
        writeClass(classesDir, "org/example/flat/shared/Unreferenced.class");

        var entries = buildSliceJar(FLAT_MANIFEST_TEXT, classesDir, "flat-order.jar");

        assertTrue(entries.contains("org/example/flat/shared/Money.class"),
                   "referenced sibling-shared class must be bundled, got: " + entries);
        assertFalse(entries.contains("org/example/flat/shared/Unreferenced.class"),
                    "the sibling-shared package is no longer bundled wholesale, got: " + entries);
    }

    /// A type that appears ONLY in descriptor form (`Lorg/example/Foo;`) must still be bundled.
    /// That is how annotation types, field types and method-signature types are written, and the
    /// bare-internal-name scan swallows the leading `L` into the first path segment — so before this
    /// was handled every `@ResourceQualifier` annotation silently fell out of the closure while
    /// ordinary CONSTANT_Class references resolved, producing bundles that looked closed and were not.
    @Test
    void createImplJarClassSet_referenceOnlyInDescriptorForm_isBundled() throws Exception {
        var classesDir = tempDir.resolve("classes");

        // The real constant pool has no separator before a descriptor, so preceding bytes run into
        // the name: an annotation reads as `ALorg/example/...`, not `Lorg/...`. Reproduce that shape —
        // a test using a clean `L` prefix passes against a fix that cannot handle real class files.
        writeClassRaw(classesDir,
                      SLICE_DIR + "/BuyTicket.class",
                      "\u0001\u0041L" + SHARED_DIR + "/SeatSoldPublisher;");
        writeClass(classesDir, SLICE_DIR + "/BuyTicketFactory.class");
        writeClass(classesDir, SHARED_DIR + "/SeatSoldPublisher.class");
        writeClass(classesDir, SHARED_DIR + "/NeverMentioned.class");

        var entries = buildSliceJar(MANIFEST_TEXT, classesDir, "descriptor.jar");

        assertTrue(entries.contains(SHARED_DIR + "/SeatSoldPublisher.class"),
                   "a type referenced only as a descriptor must be bundled, got: " + entries);
        assertFalse(entries.contains(SHARED_DIR + "/NeverMentioned.class"),
                    "descriptor handling must not turn the closure into bundle-everything, got: " + entries);
    }

    @Test
    void createImplJarClassSet_referenceWithNoCompiledClass_isSkipped() throws Exception {
        var classesDir = tempDir.resolve("classes");
        // "org/example/app/shared/Ghost" matches the internal-name scan but has no class file:
        // the intersection with compiled output is what makes a parser unnecessary here
        writeClass(classesDir, SLICE_DIR + "/BuyTicket.class", SHARED_DIR + "/Ghost", "java/lang/String");
        writeClass(classesDir, SLICE_DIR + "/BuyTicketFactory.class");
        writeClass(classesDir, SLICE_DIR + "/BuyRequest.class");

        var entries = buildSliceJar(MANIFEST_TEXT, classesDir, "ghost.jar");

        assertFalse(entries.contains(SHARED_DIR + "/Ghost.class"),
                    "a scan hit with no compiled class must be skipped, got: " + entries);
        assertTrue(entries.contains(SLICE_DIR + "/BuyTicket.class"),
                   "declared impl classes must still be bundled, got: " + entries);
    }

    /// Drives the same collection walk createImplJar performs against a real JarArchiver.
    private Set<String> buildSliceJar(String manifestText, Path classesDir, String jarName) throws Exception {
        var manifest = SliceManifest.load(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8)))
                                    .unwrap();
        var mojo = new PackageSlicesMojo();

        setClassesDirectory(mojo, classesDir.toFile());

        var jarPath = tempDir.resolve(jarName);
        var archiver = new JarArchiver();
        var bundled = new HashSet<String>();

        archiver.setDestFile(jarPath.toFile());
        // Enter through the SAME seam packaging uses. Calling the individual steps here instead — as
        // this helper first did — tests that the steps work while proving nothing about whether
        // packaging invokes them: a probe that deleted the closure call from createImplJar failed no
        // test at all.
        invokeAddBundleClasses(mojo, archiver, manifest, bundled);
        archiver.createArchive();

        return jarEntries(jarPath);
    }

    /// Class file content is never parsed — the scan looks for internal names in the raw bytes, which
    /// is where javac puts them too (UTF-8 constant pool entries).
    private static void writeClassRaw(Path classesDir, String relativePath, String rawText) throws Exception {
        var file = classesDir.resolve(relativePath);
        var magic = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, ' '};
        var body = rawText.getBytes(StandardCharsets.UTF_8);
        var content = new byte[magic.length + body.length];

        System.arraycopy(magic, 0, content, 0, magic.length);
        System.arraycopy(body, 0, content, magic.length, body.length);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    private static void writeClass(Path classesDir, String relativePath, String... references) throws Exception {
        var file = classesDir.resolve(relativePath);
        var magic = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, ' '};
        var names = String.join(" ", references)
                          .getBytes(StandardCharsets.UTF_8);
        var content = new byte[magic.length + names.length];

        System.arraycopy(magic, 0, content, 0, magic.length);
        System.arraycopy(names, 0, content, magic.length, names.length);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
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

    private static void invokeAddBundleClasses(PackageSlicesMojo mojo,
                                               JarArchiver archiver,
                                               SliceManifest manifest,
                                               Set<String> bundled) throws Exception {
        var method = PackageSlicesMojo.class.getDeclaredMethod("addBundleClasses",
                                                               JarArchiver.class,
                                                               SliceManifest.class,
                                                               Map.class,
                                                               Set.class);

        method.setAccessible(true);
        method.invoke(mojo, archiver, manifest, Map.of(), bundled);
    }
}
