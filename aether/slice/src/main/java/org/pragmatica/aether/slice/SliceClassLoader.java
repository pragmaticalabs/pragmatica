package org.pragmatica.aether.slice;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;


/// ClassLoader for individual slice isolation.
///
/// Uses child-first delegation strategy for slice code and conflict overrides,
/// delegating to parent (SharedLibraryClassLoader) for shared dependencies.
///
/// Delegation strategy:
///
///   - **Parent-first** for JDK classes (java.*, javax.*, jdk.*, sun.*) - mandatory
///   - **Child-first** for everything else - enables slice isolation
///
///
/// The URLs provided should include:
///
///   - The slice JAR itself
///   - Any conflicting dependency JARs that shadow shared versions
///
///
/// @see SharedLibraryClassLoader
@SuppressWarnings("JBCT-UTIL-02") public class SliceClassLoader extends URLClassLoader {
    private static final String JAVA_PREFIX = "java.";

    private static final String JAVAX_PREFIX = "javax.";

    private static final String JDK_PREFIX = "jdk.";

    private static final String SUN_PREFIX = "sun.";

    public SliceClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @SuppressWarnings("JBCT-EX-01") @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name);
            if (loaded != null) {return loaded;}
            if (isJdkClass(name)) {return super.loadClass(name, resolve);}
            try {
                var clazz = findClass(name);
                if (resolve) {resolveClass(clazz);}
                return clazz;
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }
    }

    private boolean isJdkClass(String name) {
        return name.startsWith(JAVA_PREFIX) || name.startsWith(JAVAX_PREFIX) || name.startsWith(JDK_PREFIX) || name.startsWith(SUN_PREFIX);
    }

    @SuppressWarnings("JBCT-RET-01") public void addSliceDependencyUrl(URL url) {
        addURL(url);
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"}) @Override public void close() throws IOException {
        super.close();
    }
}
