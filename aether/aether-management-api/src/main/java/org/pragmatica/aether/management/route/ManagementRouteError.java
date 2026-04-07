/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pragmatica.aether.management.route;

import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.http.routing.HttpMethod;
import org.pragmatica.lang.Cause;


/// Errors returned by the management route registry.
public sealed interface ManagementRouteError extends Cause {
    static NoMatch noMatch(HttpMethod method, String path) {
        return new NoMatch(method, path);
    }

    static WrongParamCount wrongParamCount(String routeName, int expected, int actual) {
        return new WrongParamCount(routeName, expected, actual);
    }

    static MissingParam missingParam(String routeName, String paramName) {
        return new MissingParam(routeName, paramName);
    }

    static AmbiguousRoutes ambiguousRoutes(String first, String second, String signature) {
        return new AmbiguousRoutes(first, second, signature);
    }

    static LocalNotForwardable localNotForwardable(String routeName) {
        return new LocalNotForwardable(routeName);
    }

    static OwnerDisconnected ownerDisconnected(TaskGroup group, String ownerNodeId) {
        return new OwnerDisconnected(group, ownerNodeId);
    }

    record NoMatch(HttpMethod method, String path) implements ManagementRouteError {
        @Override public String message() {
            return "No management route matches " + method + " " + path;
        }
    }

    record WrongParamCount(String routeName, int expected, int actual) implements ManagementRouteError {
        @Override public String message() {
            return "Route " + routeName + " expects " + expected + " parameters, got " + actual;
        }
    }

    record MissingParam(String routeName, String paramName) implements ManagementRouteError {
        @Override public String message() {
            return "Route " + routeName + " missing parameter: " + paramName;
        }
    }

    record AmbiguousRoutes(String first, String second, String signature) implements ManagementRouteError {
        @Override public String message() {
            return "Ambiguous management routes: " + first + " and " + second + " share signature " + signature;
        }
    }

    record LocalNotForwardable(String routeName) implements ManagementRouteError {
        @Override public String message() {
            return "Route " + routeName + " is marked LOCAL and cannot be forwarded";
        }
    }

    record OwnerDisconnected(TaskGroup group, String ownerNodeId) implements ManagementRouteError {
        @Override public String message() {
            return "Task group " + group + " owner " + ownerNodeId + " is not connected";
        }
    }
}
