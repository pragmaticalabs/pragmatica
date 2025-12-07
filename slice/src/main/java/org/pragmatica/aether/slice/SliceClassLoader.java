package org.pragmatica.aether.slice;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * ClassLoader for slice isolation with hybrid delegation strategy.
 *
 * <p>Delegation strategy:
 * <ul>
 *   <li><b>Parent-first</b> for framework classes (org.pragmatica.*) - ensures shared types</li>
 *   <li><b>Child-first</b> for slice classes - enables isolation between slices</li>
 * </ul>
 *
 * <p>This ensures slices are isolated from each other while sharing the framework.
 */
public class SliceClassLoader extends URLClassLoader {

    private static final String FRAMEWORK_PREFIX = "org.pragmatica.";
    private static final String JAVA_PREFIX = "java.";
    private static final String JAVAX_PREFIX = "javax.";
    private static final String JDK_PREFIX = "jdk.";
    private static final String SUN_PREFIX = "sun.";

    public SliceClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // Check if already loaded
            var loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }

            // Parent-first for JDK classes (mandatory)
            if (isJdkClass(name)) {
                return super.loadClass(name, resolve);
            }

            // Parent-first for framework classes (shared types)
            if (isFrameworkClass(name)) {
                return super.loadClass(name, resolve);
            }

            // Child-first for everything else (slice isolation)
            try {
                var clazz = findClass(name);
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            } catch (ClassNotFoundException e) {
                // Fall back to parent if not found in slice JAR
                return super.loadClass(name, resolve);
            }
        }
    }

    /**
     * Check if class is a JDK internal class that must be loaded by parent.
     */
    private boolean isJdkClass(String name) {
        return name.startsWith(JAVA_PREFIX)
            || name.startsWith(JAVAX_PREFIX)
            || name.startsWith(JDK_PREFIX)
            || name.startsWith(SUN_PREFIX);
    }

    /**
     * Check if class is part of the Pragmatica framework (shared types).
     */
    private boolean isFrameworkClass(String name) {
        return name.startsWith(FRAMEWORK_PREFIX);
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
