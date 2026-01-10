package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

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
