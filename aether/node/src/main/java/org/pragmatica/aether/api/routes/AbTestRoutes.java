package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.update.AbTestDeployment;
import org.pragmatica.aether.update.AbTestMetrics;
import org.pragmatica.aether.update.SplitRule;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.pragmatica.aether.api.ManagementApiResponses.AbTestInfo;
import static org.pragmatica.aether.api.ManagementApiResponses.AbTestListResponse;
import static org.pragmatica.aether.api.ManagementApiResponses.AbTestMetricsResponse;
import static org.pragmatica.aether.api.ManagementApiResponses.AbTestVariantMetrics;
import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.PathParameter.spacer;

/// Routes for A/B test management: create, conclude, metrics.
public final class AbTestRoutes implements RouteSource {
    private static final Cause MISSING_ARTIFACT_BASE = Causes.cause("Missing artifactBase");
    private static final Cause MISSING_VARIANTS = Causes.cause("Missing or empty variants");
    private static final Cause NOT_LEADER = Causes.cause("This operation requires the leader node");
    private static final Cause TEST_NOT_FOUND = Causes.cause("A/B test not found");

    private final Supplier<AetherNode> nodeSupplier;

    private AbTestRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static AbTestRoutes abTestRoutes(Supplier<AetherNode> nodeSupplier) {
        return new AbTestRoutes(nodeSupplier);
    }

    // Request DTOs
    record AbTestCreateRequest(String artifactBase,
                               Map<String, String> variants,
                               String splitType,
                               String splitHeader){}

    record AbTestConcludeRequest(String winner){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(// GET /api/ab-tests - List all tests
        Route.<AbTestListResponse>get("/api/ab-tests")
             .toJson(this::buildAbTestListResponse),
        // GET /api/ab-test/{testId}/metrics - Test metrics
        Route.<AbTestMetricsResponse>get("/api/ab-test")
             .withPath(aString(),
                       spacer("metrics"))
             .to((testId, _) -> buildAbTestMetricsResponse(testId))
             .asJson(),
        // GET /api/ab-test/{testId} - Single test by ID
        Route.<AbTestInfo>get("/api/ab-test")
             .withPath(aString())
             .to(this::buildAbTestResponse)
             .asJson(),
        // POST /api/ab-test/create - Create test
        Route.<AbTestInfo>post("/api/ab-test/create")
             .withBody(AbTestCreateRequest.class)
             .toJson(this::handleAbTestCreate),
        // POST /api/ab-test/{testId}/conclude - Conclude test with winner
        Route.<AbTestInfo>post("/api/ab-test")
             .withPath(aString(),
                       spacer("conclude"))
             .withBody(AbTestConcludeRequest.class)
             .to((testId, _, body) -> handleAbTestConclude(testId, body))
             .asJson());
    }

    private Promise<AbTestInfo> handleAbTestCreate(AbTestCreateRequest request) {
        return requireLeader().flatMap(_ -> parseCreateRequest(request))
                            .flatMap(parsed -> nodeSupplier.get().abTestManager()
                                                               .createTest(parsed.artifactBase(),
                                                                           parsed.variantVersions(),
                                                                           parsed.splitRule()))
                            .map(this::toAbTestInfo)
                            .onSuccess(info -> AuditLog.deploymentStart("ab-test",
                                                                        info.testId(),
                                                                        info.artifactBase(),
                                                                        info.baselineVersion(),
                                                                        "variants=" + info.variantCount()));
    }

    private Promise<AbTestInfo> handleAbTestConclude(String testId, AbTestConcludeRequest request) {
        return requireLeader().flatMap(_ -> nodeSupplier.get().abTestManager()
                                                            .concludeTest(testId,
                                                                          request.winner()))
                            .map(this::toAbTestInfo)
                            .onSuccess(info -> AuditLog.deploymentComplete("ab-test",
                                                                           info.testId(),
                                                                           info.artifactBase()));
    }

    private Promise<org.pragmatica.lang.Unit> requireLeader() {
        var node = nodeSupplier.get();
        if ( !node.isLeader()) {
            var leaderInfo = node.leader().map(id -> " Current leader: " + id.id())
                                        .or("");
            return Causes.cause(NOT_LEADER.message() + leaderInfo).promise();
        }
        return Promise.unitPromise();
    }

    private record ParsedAbTestRequest(ArtifactBase artifactBase,
                                       Map<String, Version> variantVersions,
                                       SplitRule splitRule){}

    private Promise<ParsedAbTestRequest> parseCreateRequest(AbTestCreateRequest request) {
        if ( request.artifactBase() == null) {
        return MISSING_ARTIFACT_BASE.promise();}
        if ( request.variants() == null || request.variants().isEmpty()) {
        return MISSING_VARIANTS.promise();}
        return ArtifactBase.artifactBase(request.artifactBase()).async()
                                        .flatMap(artifactBase -> parseVariants(request.variants()).async()
                                                                              .map(variants -> new ParsedAbTestRequest(artifactBase,
                                                                                                                       variants,
                                                                                                                       parseSplitRule(request))));
    }

    private static Result<Map<String, Version>> parseVariants(Map<String, String> rawVariants) {
        var parsed = new HashMap<String, Version>();
        for ( var entry : rawVariants.entrySet()) {
            var result = Version.version(entry.getValue());
            if ( result.isFailure()) {
            return Causes.cause("Invalid version for variant " + entry.getKey() + ": " + entry.getValue()).result();}
            result.onSuccess(v -> parsed.put(entry.getKey(), v));
        }
        return Result.success(Map.copyOf(parsed));
    }

    private static SplitRule parseSplitRule(AbTestCreateRequest request) {
        var splitType = request.splitType() != null
                        ? request.splitType()
                        : "header-hash";
        var headerName = request.splitHeader() != null
                         ? request.splitHeader()
                         : "X-Request-Id";
        return SplitRule.HeaderHashSplit.headerHashSplit(headerName, 2);
    }

    private AbTestListResponse buildAbTestListResponse() {
        var tests = nodeSupplier.get().abTestManager()
                                    .allTests()
                                    .stream()
                                    .map(this::toAbTestInfo)
                                    .toList();
        return new AbTestListResponse(tests);
    }

    private Promise<AbTestInfo> buildAbTestResponse(String testId) {
        return nodeSupplier.get().abTestManager()
                               .getTest(testId)
                               .map(this::toAbTestInfo)
                               .async(TEST_NOT_FOUND);
    }

    private Promise<AbTestMetricsResponse> buildAbTestMetricsResponse(String testId) {
        var metrics = nodeSupplier.get().abTestManager()
                                      .getMetrics(testId);
        return Promise.success(toAbTestMetricsResponse(metrics));
    }

    private AbTestInfo toAbTestInfo(AbTestDeployment test) {
        return new AbTestInfo(test.testId(),
                              test.artifactBase().asString(),
                              test.baselineVersion().toString(),
                              test.state().name(),
                              test.variantVersions().size(),
                              test.createdAt(),
                              test.updatedAt());
    }

    private AbTestMetricsResponse toAbTestMetricsResponse(AbTestMetrics metrics) {
        var variants = metrics.variantMetrics().entrySet()
                                             .stream()
                                             .collect(Collectors.toMap(Map.Entry::getKey,
                                                                       entry -> toVariantMetrics(entry.getValue())));
        return new AbTestMetricsResponse(metrics.testId(), variants, metrics.collectedAt());
    }

    private AbTestVariantMetrics toVariantMetrics(AbTestMetrics.VariantMetrics vm) {
        return new AbTestVariantMetrics(vm.variant(),
                                        vm.version().toString(),
                                        vm.requestCount(),
                                        vm.errorRate(),
                                        vm.avgLatencyMs());
    }
}
