package org.pragmatica.jbct.maven;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PackageSlicesMojo version mapping and transformation logic.
 */
class PackageSlicesMojoTest {
    @Test
    void buildVersionMap_parsesSlicesDependencies() {
        var depsContent = """
            [slices]
            org.example:slice-analytics:^1.0.0
            org.example:slice-inventory:^2.1.0
            org.example:slice-orders:~3.0.0

            [infrastructure]
            org.pragmatica-lite:core:0.9.10
            """;
        var mojo = new PackageSlicesMojo();
        Map<String, String> map = invokePrivate(mojo, "buildVersionMap", String.class, depsContent);
        assertEquals("1.0.0", map.get("org.example:slice-analytics"));
        assertEquals("2.1.0", map.get("org.example:slice-inventory"));
        assertEquals("3.0.0", map.get("org.example:slice-orders"));
        assertNull(map.get("org.pragmatica-lite:core"));
    }

    @Test
    void buildVersionMap_handlesEmptyContent() {
        var mojo = new PackageSlicesMojo();
        Map<String, String> map = invokePrivate(mojo, "buildVersionMap", String.class, "");
        assertTrue(map.isEmpty());
    }

    @Test
    void buildVersionMap_handlesNullContent() {
        var mojo = new PackageSlicesMojo();
        Map<String, String> map = invokePrivate(mojo, "buildVersionMap", String.class, (String) null);
        assertTrue(map.isEmpty());
    }

    @Test
    void buildVersionMap_handlesNoSlicesSection() {
        var depsContent = """
            [infrastructure]
            org.pragmatica-lite:core:0.9.10
            """;
        var mojo = new PackageSlicesMojo();
        Map<String, String> map = invokePrivate(mojo, "buildVersionMap", String.class, depsContent);
        assertTrue(map.isEmpty());
    }

    @Test
    void stripSemverPrefix_removesCaretPrefix() {
        var mojo = new PackageSlicesMojo();
        String result = invokePrivate(mojo, "stripSemverPrefix", String.class, "^1.2.3");
        assertEquals("1.2.3", result);
    }

    @Test
    void stripSemverPrefix_removesTildePrefix() {
        var mojo = new PackageSlicesMojo();
        String result = invokePrivate(mojo, "stripSemverPrefix", String.class, "~1.2.3");
        assertEquals("1.2.3", result);
    }

    @Test
    void stripSemverPrefix_preservesVersionWithoutPrefix() {
        var mojo = new PackageSlicesMojo();
        String result = invokePrivate(mojo, "stripSemverPrefix", String.class, "1.2.3");
        assertEquals("1.2.3", result);
    }

    @SuppressWarnings("unchecked")
    private <T> T invokePrivate(Object target, String methodName, Class<?> paramType, Object arg) {
        try{
            var method = target.getClass()
                               .getDeclaredMethod(methodName, paramType);
            method.setAccessible(true);
            return (T) method.invoke(target, arg);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke " + methodName, e);
        }
    }
}
