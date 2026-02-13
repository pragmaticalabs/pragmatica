package org.pragmatica.aether.http.handler;

import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.serialization.ClassRegistrator;

import java.util.function.Consumer;

/// Registers HTTP handler classes for Fury serialization.
/// Required for remote slice invocation when HttpRequestContext is serialized.
public final class HttpHandlerClassRegistrator implements ClassRegistrator {
    public static final HttpHandlerClassRegistrator INSTANCE = new HttpHandlerClassRegistrator();

    private HttpHandlerClassRegistrator() {}

    public static HttpHandlerClassRegistrator httpHandlerClassRegistrator() {
        return INSTANCE;
    }

    @Override
    public void registerClasses(Consumer<Class<?>> consumer) {
        consumer.accept(HttpRequestContext.class);
        consumer.accept(SecurityContext.class);
        consumer.accept(Principal.class);
        consumer.accept(Role.class);
    }
}
