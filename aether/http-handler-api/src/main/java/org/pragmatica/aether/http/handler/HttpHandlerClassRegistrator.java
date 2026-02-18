package org.pragmatica.aether.http.handler;

import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.serialization.ClassRegistrator;

import java.util.List;

/// Registers HTTP handler classes for Fury serialization.
/// Required for remote slice invocation when HttpRequestContext is serialized.
public final class HttpHandlerClassRegistrator implements ClassRegistrator {
    public static final HttpHandlerClassRegistrator INSTANCE = new HttpHandlerClassRegistrator();

    private static final List<Class<?>> CLASSES = List.of(HttpRequestContext.class,
                                                          SecurityContext.class,
                                                          Principal.class,
                                                          Role.class);

    private HttpHandlerClassRegistrator() {}

    public static HttpHandlerClassRegistrator httpHandlerClassRegistrator() {
        return INSTANCE;
    }

    @Override
    public List<Class<?>> classesToRegister() {
        return CLASSES;
    }
}
