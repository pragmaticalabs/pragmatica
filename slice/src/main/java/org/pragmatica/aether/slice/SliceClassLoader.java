package org.pragmatica.aether.slice;

import java.net.URL;
import java.net.URLClassLoader;

public class SliceClassLoader extends URLClassLoader {
    public SliceClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }
}
