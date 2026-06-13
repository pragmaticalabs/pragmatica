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

    private boolean isJdkClass(String name) {
        return name.startsWith(JAVA_PREFIX) || name.startsWith(JAVAX_PREFIX) || name.startsWith(JDK_PREFIX) || name.startsWith(SUN_PREFIX);
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
