package org.pragmatica.aether.slice;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * ClassLoader for individual slice isolation.
 * <p>
 * Uses child-first delegation strategy for slice code and conflict overrides,
 * delegating to parent (SharedLibraryClassLoader) for shared dependencies.
 * <p>
 * Delegation strategy:
 * <ul>
 *   <li><b>Parent-first</b> for JDK classes (java.*, javax.*, jdk.*, sun.*) - mandatory</li>
 *   <li><b>Child-first</b> for everything else - enables slice isolation</li>
 * </ul>
 * <p>
 * The URLs provided should include:
 * <ul>
 *   <li>The slice JAR itself</li>
 *   <li>Any conflicting dependency JARs that shadow shared versions</li>
 * </ul>
 *
 * @see SharedLibraryClassLoader
 */
public class SliceClassLoader extends URLClassLoader {
    private static final String JAVA_PREFIX = "java.";
    private static final String JAVAX_PREFIX = "javax.";
    private static final String JDK_PREFIX = "jdk.";
    private static final String SUN_PREFIX = "sun.";

    /**
     * Create a new SliceClassLoader.
     *
     * @param urls   URLs to slice JAR and any conflicting dependency JARs
     * @param parent Parent classloader (typically SharedLibraryClassLoader)
     */
    public SliceClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class< ? > loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // Check if already loaded
            var loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            // Parent-first for JDK classes (mandatory - cannot be overridden)
            if (isJdkClass(name)) {
                return super.loadClass(name, resolve);
            }
            // Child-first for everything else (slice isolation)
            // This allows slice code and conflict overrides to shadow parent classes
            try{
                var clazz = findClass(name);
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            } catch (ClassNotFoundException e) {
                // Fall back to parent (SharedLibraryClassLoader -> Node ClassLoader)
                return super.loadClass(name, resolve);
            }
        }
    }

    /**
     * Check if class is a JDK internal class that must be loaded by parent.
     * These classes cannot be overridden in child classloaders.
     */
    private boolean isJdkClass(String name) {
        return name.startsWith(JAVA_PREFIX) || name.startsWith(JAVAX_PREFIX) || name.startsWith(JDK_PREFIX) || name.startsWith(SUN_PREFIX);
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
