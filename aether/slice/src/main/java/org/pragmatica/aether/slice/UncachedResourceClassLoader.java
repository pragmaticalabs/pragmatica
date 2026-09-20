// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;


/// A [URLClassLoader] whose resource reads never touch the JVM-shared `jar:` URL cache.
///
/// `URLClassLoader.getResourceAsStream` opens a caching `JarURLConnection`, which answers with the
/// `JarFile` that every other opener of that jar URL in the process shares, and registers it in the
/// loader's closeables. `close()` then closes a jar other readers are in the middle of, and a
/// `JarURLConnection.connect` racing that close re-inserts the CLOSED instance into the JDK's
/// `JarFileFactory`, where nothing ever evicts it: every later read of that jar URL in the JVM fails
/// with `zip file closed` (s25-inv1277: one slice unload poisoned every later blueprint apply of the
/// same artifact). With `useCaches=false` the connection opens a private `JarFile` that the returned
/// stream closes, so closing the loader closes only what the loader itself opened.
///
/// Class loading is unaffected: for `file:` jars `URLClassPath` already opens a private `JarFile`,
/// and `getResource`/`findResource` return URLs without opening a connection.
public class UncachedResourceClassLoader extends URLClassLoader {
    public UncachedResourceClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /// Same contract as the JDK's: `null` when the resource is absent or cannot be opened.
    @SuppressWarnings({"JBCT-RET-03", "JBCT-EX-01"})
    @Override
    public InputStream getResourceAsStream(String name) {
        var url = getResource(name);

        if (url == null) {
            return null;
        }

        try {
            var connection = url.openConnection();

            connection.setUseCaches(false);

            return connection.getInputStream();
        } catch (IOException e) {
            return null;
        }
    }
}
