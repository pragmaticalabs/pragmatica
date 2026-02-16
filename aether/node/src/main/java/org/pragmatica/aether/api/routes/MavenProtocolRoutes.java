package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;

import java.util.function.Supplier;

import static org.pragmatica.http.HttpMethod.GET;
import static org.pragmatica.http.HttpMethod.POST;
import static org.pragmatica.http.HttpMethod.PUT;

/// Routes for Maven repository protocol: binary artifact GET/PUT/POST.
///
///
/// These routes remain as {@link RouteHandler} because they require dynamic content types
/// determined at runtime based on file extension. The Route API requires content types to be
/// fixed at route definition time.
///
///
/// URL patterns:
///
///   - `GET /repository/{groupPath`/{artifactId}/{version}/{file}}
///   - `PUT /repository/{groupPath`/{artifactId}/{version}/{file}}
///   - `POST /repository/{groupPath`/{artifactId}/{version}/{file}}
///   - `GET /repository/{groupPath`/{artifactId}/maven-metadata.xml}
///
public final class MavenProtocolRoutes implements RouteHandler {
    private static final String REPOSITORY_PREFIX = "/repository/";
    private static final String REPOSITORY_INFO_PREFIX = "/repository/info/";

    private final Supplier<AetherNode> nodeSupplier;

    private MavenProtocolRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static MavenProtocolRoutes mavenProtocolRoutes(Supplier<AetherNode> nodeSupplier) {
        return new MavenProtocolRoutes(nodeSupplier);
    }

    @Override
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        var path = ctx.path();
        var method = ctx.method();
        // Skip /repository/info/ - handled by RepositoryRoutes
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

    private void handleGet(ResponseWriter response, String uri) {
        var node = nodeSupplier.get();
        node.mavenProtocolHandler()
            .handleGet(uri)
            .onSuccess(r -> sendProtocolResponse(response, r))
            .onFailure(response::internalError);
    }

    private void handlePut(ResponseWriter response, String uri, byte[] content) {
        var node = nodeSupplier.get();
        node.mavenProtocolHandler()
            .handlePut(uri, content)
            .onSuccess(r -> sendProtocolResponse(response, r))
            .onFailure(response::internalError);
    }

    private void sendProtocolResponse(ResponseWriter response, MavenResponse mavenResponse) {
        var status = findHttpStatus(mavenResponse.statusCode());
        response.write(status,
                       mavenResponse.content(),
                       ContentType.contentType(mavenResponse.contentType(), ContentCategory.BINARY));
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
