// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;


@SuppressWarnings("JBCT-UTIL-02")
public class SliceClassLoader extends URLClassLoader {
    private static final String JAVA_PREFIX = "java.";
    private static final String JAVAX_PREFIX = "javax.";
    private static final String JDK_PREFIX = "jdk.";
    private static final String SUN_PREFIX = "sun.";
    private static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();

    public SliceClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @SuppressWarnings("JBCT-EX-01")
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name);

            if (loaded != null) {
                return loaded;
            }

            if (isJdkClass(name)) {
                return super.loadClass(name, resolve);
            }

            try {
                var clazz = findClass(name);

                if (resolve) {
                    resolveClass(clazz);
                }

                return clazz;
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }
    }

    /// `java.` / `jdk.` / `sun.` are JDK namespaces wholesale. `javax.` is NOT (#613):
    /// `javax.inject`, `javax.servlet`, `javax.annotation` are ordinary third-party artifacts a
    /// slice must be able to bundle — that per-slice version independence is what the child-first
    /// loader exists for. But parts of `javax.` ARE shipped by JDK modules (`javax.xml` JAXP,
    /// `javax.crypto`, `javax.net`, `javax.sql`, `javax.management`, ...), and loading those
    /// child-first splits one namespace across two loaders — the classic xml-apis
    /// ClassCastException. A hand-maintained package list has sharp edges (`javax.annotation` is
    /// third-party while `javax.annotation.processing` is JDK) and drifts across releases, so the
    /// predicate is the definition itself: parent-first iff the platform loader resolves the class.
    private boolean isJdkClass(String name) {
        if (name.startsWith(JAVA_PREFIX) || name.startsWith(JDK_PREFIX) || name.startsWith(SUN_PREFIX)) {
            return true;
        }

        return name.startsWith(JAVAX_PREFIX) && platformResolves(name);
    }

    /// Probe, not list: the JVM caches negative lookups poorly but each class is asked once per
    /// slice loader and the answer is then held by `findLoadedClass`, so the cost is one probe
    /// per distinct `javax.*` class per slice.
    @SuppressWarnings("JBCT-EX-01")
    private boolean platformResolves(String name) {
        try {
            Class.forName(name, false, PLATFORM);

            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @SuppressWarnings("JBCT-RET-01")
    public void addSliceDependencyUrl(URL url) {
        addURL(url);
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    @Override
    public void close() throws IOException {
        super.close();
    }
}
