// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Contract;

import java.util.function.Supplier;

import static org.pragmatica.http.HttpMethod.GET;
import static org.pragmatica.http.HttpMethod.POST;
import static org.pragmatica.http.HttpMethod.PUT;


public final class MavenProtocolRoutes implements RouteHandler {
    private static final String REPOSITORY_PREFIX = ManagementRoute.ARTIFACT_GET.prefix() + "/";
    private static final String REPOSITORY_INFO_PREFIX = ManagementRoute.ARTIFACT_INFO.prefix() + "/";

    private final Supplier<ManageableNode> nodeSupplier;

    private MavenProtocolRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static MavenProtocolRoutes mavenProtocolRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new MavenProtocolRoutes(nodeSupplier);
    }

    @Override
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        var path = ctx.path();
        var method = ctx.method();

        if (!path.startsWith(REPOSITORY_PREFIX) || path.startsWith(REPOSITORY_INFO_PREFIX)) {
            return false;
        }

        if (method == GET) {
            handleGet(response, path);

            return true;
        }

        if (method == POST || method == PUT) {
            handlePut(response, path, ctx.body());

            return true;
        }

        return false;
    }

    @Contract
    private void handleGet(ResponseWriter response, String uri) {
        var node = nodeSupplier.get();

        node.mavenProtocolHandler().handleGet(uri).onSuccess(r -> sendProtocolResponse(response, r)).onFailure(response::internalError);
    }

    @Contract
    private void handlePut(ResponseWriter response, String uri, byte[] content) {
        var node = nodeSupplier.get();

        node.mavenProtocolHandler().handlePut(uri, content).onSuccess(r -> sendProtocolResponse(response, r)).onFailure(response::internalError);
    }

    private void sendProtocolResponse(ResponseWriter response, MavenResponse mavenResponse) {
        var status = findHttpStatus(mavenResponse.statusCode());

        response.write(status,
                       mavenResponse.content(),
                       ContentType.contentType(mavenResponse.contentType(), categoryFor(mavenResponse.contentType())));
    }

    private ContentCategory categoryFor(String contentType) {
        if (contentType == null) {
            return ContentCategory.BINARY;
        }

        if (contentType.startsWith("application/json") || contentType.startsWith("application/problem+json")) {
            return ContentCategory.JSON;
        }

        if (contentType.startsWith("application/xml")) {
            return ContentCategory.XML;
        }

        if (contentType.startsWith("text/")) {
            return ContentCategory.TEXT;
        }

        return ContentCategory.BINARY;
    }

    private HttpStatus findHttpStatus(int code) {
        for (var status : HttpStatus.values()) {
            if (status.code() == code) {
                return status;
            }
        }

        return HttpStatus.OK;
    }
}
