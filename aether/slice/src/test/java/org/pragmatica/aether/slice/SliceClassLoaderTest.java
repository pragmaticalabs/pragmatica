// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SliceClassLoaderTest {

    @TempDir
    Path tempDir;

    // === JDK Class Delegation Tests ===

    @Test
    void loadClass_javaClasses_delegatesToParent() throws ClassNotFoundException {
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());

        var stringClass = classLoader.loadClass("java.lang.String");

        assertThat(stringClass).isEqualTo(String.class);
        assertThat(stringClass.getClassLoader()).isNull(); // Bootstrap loader
    }

    @Test
    void loadClass_javaxClasses_delegatesToParent() throws ClassNotFoundException {
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());

        // javax.management is standard JDK
        var mbeanClass = classLoader.loadClass("javax.management.MBeanServer");

        assertThat(mbeanClass.getClassLoader()).isNull(); // Bootstrap loader
    }

    // === Framework Class Delegation Tests ===

    @Test
    void loadClass_pragmaticaClasses_delegatesToParent() throws ClassNotFoundException {
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());

        // Load a known framework class
        var resultClass = classLoader.loadClass("org.pragmatica.lang.Result");

        // Should be same class as parent loaded
        assertThat(resultClass.getClassLoader()).isNotNull();
        assertThat(resultClass).isEqualTo(org.pragmatica.lang.Result.class);
    }

    @Test
    void loadClass_aetherSliceApi_delegatesToParent() throws ClassNotFoundException {
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());

        var sliceClass = classLoader.loadClass("org.pragmatica.aether.slice.Slice");

        assertThat(sliceClass).isEqualTo(org.pragmatica.aether.slice.Slice.class);
    }

    // === javax.* Delegation (#613): third-party javax is slice-local, JDK javax stays parent-first ===

    /// The bug #613 fixes: `javax.inject` is an ordinary third-party artifact, and blanket
    /// parent-first delegation for `javax.` meant a slice could not bundle its own copy —
    /// before the fix this call ended in ClassNotFoundException.
    @Test
    void loadClass_sliceBundledThirdPartyJavax_resolvesSliceCopy() throws Exception {
        var classes = compile("javax/inject/Named.java",
                              "package javax.inject; public class Named {}",
                              List.of());
        var classLoader = new SliceClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());

        var named = classLoader.loadClass("javax.inject.Named");

        assertThat(named.getClassLoader()).isSameAs(classLoader);
    }

    /// The reason the fix probes instead of dropping `javax.` wholesale: `javax.xml` is shipped
    /// by the JDK's `java.xml` module, and honoring a slice-bundled shadow (xml-apis style) would
    /// split the namespace across two loaders — the classic cross-loader ClassCastException. The
    /// shadow must be IGNORED. (Shadow bytes minted via --patch-module; vanilla javac refuses the
    /// split package.)
    @Test
    void loadClass_jdkOwnedJavax_ignoresSliceShadow() throws Exception {
        var classes = compile("javax/xml/parsers/DocumentBuilderFactory.java",
                              "package javax.xml.parsers; public class DocumentBuilderFactory {}",
                              List.of("--patch-module", "java.xml={SRC}"));
        var classLoader = new SliceClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());

        var factory = classLoader.loadClass("javax.xml.parsers.DocumentBuilderFactory");

        assertThat(factory).isEqualTo(javax.xml.parsers.DocumentBuilderFactory.class);
    }

    /// Mutation check from #613's acceptance: a slice-local `java.lang` shadow must still be
    /// ignored. The shadow must be of an EXISTING class: a NOVEL java.lang class ends in the
    /// JDK's own defineClass defense (SecurityException) on the correct code AND on a predicate
    /// mutant — `super.loadClass` is parent-first-then-self, so it reaches the slice URLs after
    /// the parent misses — which distinguishes nothing (measured, the first version of this test
    /// expected CNFE and learned better). With a bundled `java.lang.String` shadow the outcomes
    /// split: correct routing returns the bootstrap String; a mutant that drops the `java.`
    /// prefix goes child-first into defineClass and dies with "Prohibited package name".
    @Test
    void loadClass_bundledJavaLangShadow_isIgnored_bootstrapClassWins() throws Exception {
        var classes = compile("java/lang/String.java",
                              "package java.lang; public class String {}",
                              List.of("--patch-module", "java.base={SRC}"));
        var classLoader = new SliceClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());

        var string = classLoader.loadClass("java.lang.String");

        assertThat(string).isEqualTo(String.class);
        assertThat(string.getClassLoader()).isNull();
    }

    /// Compiles one source file into tempDir and returns the classes directory. `{SRC}` in
    /// options is replaced with the source root (for --patch-module).
    private Path compile(String relativePath, String source, List<String> options) throws IOException {
        var srcRoot = tempDir.resolve("src");
        var sourceFile = srcRoot.resolve(relativePath);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);

        var classes = tempDir.resolve("classes");
        Files.createDirectories(classes);

        var args = new ArrayList<String>();
        for (var option : options) {
            args.add(option.replace("{SRC}", srcRoot.toString()));
        }
        args.add("-d");
        args.add(classes.toString());
        args.add(sourceFile.toString());

        var result = ToolProvider.getSystemJavaCompiler()
                                 .run(null, null, null, args.toArray(String[]::new));
        assertThat(result).as("javac exit code").isZero();

        return classes;
    }

    // === Child-First Loading Tests ===

    @Test
    void loadClass_nonFrameworkClasses_attemptsChildFirst() throws ClassNotFoundException {
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());

        // This class exists in parent but should be tried child-first
        // Since no JARs are provided, it falls back to parent
        var testClass = classLoader.loadClass("org.junit.jupiter.api.Test");

        assertThat(testClass).isNotNull();
    }

    // === Resource Cleanup Tests ===

    @Test
    void close_normalOperation_releasesResources() throws IOException {
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());

        classLoader.close();

        // No exception means success
    }

    @Test
    void close_multipleCalls_succeedsIdempotently() throws IOException {
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());

        classLoader.close();
        classLoader.close();

        // No exception means success
    }

    // === URL Handling Tests ===

    @Test
    void constructor_emptyUrlArray_succeeds() throws ClassNotFoundException {
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());

        // Should still load framework classes
        var resultClass = classLoader.loadClass("org.pragmatica.lang.Result");

        assertThat(resultClass).isNotNull();
    }

    @Test
    void constructor_jarUrl_succeeds() throws Exception {
        // Create a dummy JAR file
        var jarFile = tempDir.resolve("test.jar");
        Files.createFile(jarFile);

        var url = jarFile.toUri().toURL();
        var classLoader = new SliceClassLoader(new URL[]{url}, getClass().getClassLoader());

        // Should not throw
        assertThat(classLoader.getURLs()).hasSize(1);
        assertThat(classLoader.getURLs()[0]).isEqualTo(url);

        classLoader.close();
    }

    // === Class Loading Lock Tests ===

    @Test
    void loadClass_sameClass_loadedOnce() throws ClassNotFoundException {
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());

        var first = classLoader.loadClass("java.lang.String");
        var second = classLoader.loadClass("java.lang.String");

        assertThat(first).isSameAs(second);
    }

    // === Isolation Verification Tests ===

    @Test
    void loadClass_differentClassLoaders_shareFrameworkClasses() throws ClassNotFoundException {
        var classLoader1 = new SliceClassLoader(new URL[0], getClass().getClassLoader());
        var classLoader2 = new SliceClassLoader(new URL[0], getClass().getClassLoader());

        var class1 = classLoader1.loadClass("org.pragmatica.lang.Result");
        var class2 = classLoader2.loadClass("org.pragmatica.lang.Result");

        // Same class object because parent-first for framework
        assertThat(class1).isSameAs(class2);
    }

    @Test
    void loadClass_jdkClasses_sharedAcrossClassLoaders() throws ClassNotFoundException {
        var classLoader1 = new SliceClassLoader(new URL[0], getClass().getClassLoader());
        var classLoader2 = new SliceClassLoader(new URL[0], getClass().getClassLoader());

        var class1 = classLoader1.loadClass("java.util.ArrayList");
        var class2 = classLoader2.loadClass("java.util.ArrayList");

        assertThat(class1).isSameAs(class2);
    }
}
