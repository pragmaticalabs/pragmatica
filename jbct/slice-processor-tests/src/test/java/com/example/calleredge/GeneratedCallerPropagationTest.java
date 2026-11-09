// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package com.example.calleredge;

import com.example.callerleaf.LeafSliceFactory;
import com.example.callerleaf.Note;
import com.example.callerleaf.Seen;
import com.example.callermid.MidSlice;
import com.example.callermid.MidSliceFactory;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.slice.CallerIdentity;
import org.pragmatica.aether.slice.CallerRequest;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2157 part b, against the GENERATED factories and router: the caller's [SecurityContext], exactly as the edge
/// validated it, reaches a slice method's explicit `SecurityContext`/`Principal` parameter at the edge, one hop and
/// two hops away. Between slices it travels beside the payload, the way the invoker carries it on the invoke message
/// ([Wire] reproduces that path: what the sender puts on the message, and how the receiving bridge hands it on). An
/// unauthenticated caller travels as "no identity"; nothing is read from ambient state.
class GeneratedCallerPropagationTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final SecurityContext ALICE = SecurityContext.securityContext(Principal.principal("user:alice").unwrap(),
                                                                                 Set.of(Role.role("auditor").unwrap()),
                                                                                 Map.of("token", "secret-jwt"),
                                                                                 AuthorizationRole.VIEWER);
    private static final SecurityContext MALLORY = SecurityContext.securityContext("mallory", Set.of(Role.ADMIN)).unwrap();
    private static final String NOTE_JSON = "{\"text\":\"t\"}";

    private final Wire wire = new Wire();
    private SliceRouter router;
    private MidSlice mid;

    @BeforeEach
    void setUp() {
        var context = SliceCreationContext.sliceCreationContext(wire, unused(ResourceProviderFacade.class));

        wire.host(LeafSliceFactory.leafSliceSlice(context).await().unwrap());
        wire.host(MidSliceFactory.midSliceSlice(context).await().unwrap());
        mid = MidSliceFactory.midSlice(context).await().unwrap();
        router = new EdgeSliceRoutes().create(EdgeSliceFactory.edgeSlice(context).await().unwrap(),
                                              JsonMapper.defaultJsonMapper());
    }

    // ---- edge ---------------------------------------------------------------------------------------------------

    @Test
    void edgeCall_securityContextParam_isTheContextOnTheRequest() {
        assertThat(seenAt("GET", "/api/caller/whoami", new byte[0], ALICE)).isEqualTo(new Seen("user:alice", List.of("auditor"), "edge"));
    }

    @Test
    void edgeCall_principalParam_isThePrincipalOfTheContextOnTheRequest_afterTheBodyDecode() {
        assertThat(seenAt("POST", "/api/caller/local", json(NOTE_JSON), ALICE).principal()).isEqualTo("user:alice");
    }

    /// The generated route never reads the ambient holder: a different context bound around the dispatch is ignored.
    @Test
    void edgeCall_ignoresTheAmbientHolder() {
        var seen = ScopedValueRun.call(MALLORY, () -> seenAt("GET", "/api/caller/whoami", new byte[0], ALICE));

        assertThat(seen.principal()).isEqualTo("user:alice");
    }

    @Test
    void edgeCall_unauthenticatedRequest_isAnonymous_evenWithAHolderBound() {
        var seen = ScopedValueRun.call(MALLORY, () -> seenAt("GET", "/api/caller/whoami", new byte[0], SecurityContext.securityContext()));

        assertThat(seen).isEqualTo(new Seen("anonymous", List.of(), "edge"));
    }

    // ---- one hop ------------------------------------------------------------------------------------------------

    @Test
    void oneHop_theCalleeReceivesTheCallersContext_andItTravelsBesideThePayload() {
        var seen = await(mid.relay(ALICE, new Note("t")));

        assertThat(seen).isEqualTo(new Seen("user:alice", List.of("auditor"), "t>mid>leaf"));
        assertThat(wire.onTheWire()).containsExactly(Option.some(ALICE));
        assertThat(wire.payloads()).containsExactly(new Note("t>mid"));
    }

    @Test
    void oneHop_contextOnlyMethod_sendsUnitAsThePayload() {
        assertThat(await(mid.relayContextOnly(ALICE))).isEqualTo(new Seen("user:alice", List.of("auditor"), "leaf"));
        assertThat(wire.payloads()).containsExactly(Unit.unit());
    }

    @Test
    void oneHop_principalBesideTheContext_isThePrincipalOfTheCallersContext() {
        assertThat(await(mid.relayBoth(ALICE, new Note("t"))).trail()).isEqualTo("t>mid>leaf:principal=user:alice");
        assertThat(wire.payloads()).containsExactly(new Note("t>mid"));
    }

    @Test
    void oneHop_unauthenticatedCaller_travelsAsNoIdentity_andArrivesAnonymous() {
        assertThat(await(mid.relay(SecurityContext.securityContext(), new Note("t"))).principal()).isEqualTo("anonymous");
        assertThat(wire.onTheWire()).containsExactly(Option.none());
    }

    // ---- two hops -----------------------------------------------------------------------------------------------

    @Test
    void twoHops_edgeToMidToLeaf_theLeafSeesTheContextTheEdgeValidated() {
        var seen = seenAt("POST", "/api/caller/enter", json(NOTE_JSON), ALICE);

        assertThat(seen).isEqualTo(new Seen("user:alice", List.of("auditor"), "t>edge>mid>leaf"));
        assertThat(wire.onTheWire()).containsExactly(Option.some(ALICE), Option.some(ALICE));
    }

    @Test
    void twoHops_unauthenticatedEdgeRequest_neverPutsAnIdentityOnTheWire() {
        var seen = seenAt("POST", "/api/caller/enter", json(NOTE_JSON), SecurityContext.securityContext());

        assertThat(seen).isEqualTo(new Seen("anonymous", List.of(), "t>edge>mid>leaf"));
        assertThat(wire.onTheWire()).containsExactly(Option.none(), Option.none());
    }

    // ---- the receiving method ------------------------------------------------------------------------------------

    @Test
    void receiver_declaresCallerRequest_onlyForMethodsTakingASecurityContext() {
        assertThat(leafMethod("whoAmI").parameterType().rawType()).isEqualTo(CallerRequest.class);
        assertThat(leafMethod("withHeaders").parameterType().rawType()).isEqualTo(CallerRequest.class);
        assertThat(leafMethod("principalOnly").parameterType().rawType()).isNotEqualTo(CallerRequest.class);
    }

    /// A forged identity is identity data only: one that is not an authenticated [SecurityContext] (an anonymous
    /// principal dressed with ADMIN, or a foreign [CallerIdentity]) reaches the method as the anonymous context; an
    /// omitted one likewise. Nothing on this path consults route security.
    @Test
    void receiver_forgedOrOmittedIdentity_grantsNothing() {
        var forgedAnonymousAdmin = SecurityContext.securityContext(Principal.ANONYMOUS, Set.of(Role.ADMIN), Map.of(), AuthorizationRole.ADMIN);
        CallerIdentity foreign = new CallerIdentity() {};

        for (var caller : List.<Option<CallerIdentity>>of(Option.some(forgedAnonymousAdmin), Option.some(foreign), Option.none())) {
            assertThat(applyLeaf("whoAmI", CallerRequest.callerRequest(new Note("f"), caller)))
                .isEqualTo(new Seen("anonymous", List.of(), "f>leaf"));
        }
    }

    @Test
    void receiver_payloadWithHeaders_isTheWireRecordWithoutTheSecurityParameters() {
        var payload = new LeafSliceFactory.WithHeadersWireRequest(RequestHeaders.requestHeaders(Map.of("X-Probe", List.of("p"))),
                                                                  new Note("h"));

        assertThat(applyLeaf("withHeaders", CallerRequest.callerRequest(payload, Option.some(ALICE))))
            .isEqualTo(new Seen("user:alice", List.of("auditor"), "h>leaf:probe=p"));
    }

    // ---- generated source ----------------------------------------------------------------------------------------

    @Test
    void generatedSources_readNoAmbientSecurity() throws Exception {
        assertThat(generated("calleredge", "EdgeSliceRoutes.java")).doesNotContain("SecurityContextHolder")
                                                                   .doesNotContain("ScopedValue")
                                                                   .contains("RequestSecurity.securityOf(__request)");
        assertThat(generated("callermid", "MidSliceFactory.java")).doesNotContain("SecurityContextHolder")
                                                                  .contains("SecurityContext.callerIdentity(context)");
    }

    /// Two payload parameters remain beside the context: the sender's proxy record and the receiver's wire record hold
    /// exactly those (never the security parameters), and the receiver registers a codec for its wire record.
    @Test
    void generatedSources_payloadRecordsExcludeTheSecurityParameters() throws Exception {
        assertThat(generated("callermid", "MidSliceFactory.java"))
            .contains("record leaf_WithHeadersRequest(RequestHeaders headers, Note note) {}")
            .contains("withHeadersHandle.invoke(CallerRequest.callerRequest(new leaf_WithHeadersRequest(");
        assertThat(generated("callerleaf", "LeafSliceFactory.java"))
            .contains("public record WithHeadersWireRequest(RequestHeaders headers, Note note) {}")
            .contains("SliceCodec.deterministicTag(\"com.example.callerleaf.LeafSliceFactory.WithHeadersWireRequest\")");
    }

    // ---- harness ------------------------------------------------------------------------------------------------

    private Seen seenAt(String method, String path, byte[] body, SecurityContext security) {
        var response = router.handle(HttpRequestContext.httpRequestContext(path,
                                                                           method,
                                                                           Map.of(),
                                                                           Map.of("Content-Type", List.of("application/json")),
                                                                           body,
                                                                           "req_caller",
                                                                           security))
                             .await(AWAIT)
                             .fold(cause -> fail("router failed: " + cause.message()), r -> r);

        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);

        return JsonMapper.defaultJsonMapper()
                         .readString(new String(response.body(), StandardCharsets.UTF_8), new TypeToken<Seen>() {})
                         .unwrap();
    }

    private static <T> T await(Promise<T> promise) {
        return promise.await(AWAIT).fold(cause -> fail("call failed: " + cause.message()), value -> value);
    }

    private SliceMethod<?, ?> leafMethod(String name) {
        return wire.method(name);
    }

    @SuppressWarnings("unchecked")
    private Seen applyLeaf(String name, Object argument) {
        return await(((SliceMethod<Seen, Object>) leafMethod(name)).apply(argument));
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String generated(String pkg, String file) throws Exception {
        var relative = Paths.get("target", "generated-sources", "annotations", "com", "example", pkg, file);
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var candidate = moduleDir.resolve(relative);
        Path path = Files.exists(candidate)
                    ? candidate
                    : moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);

        return Files.readString(path);
    }

    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
                                                new Class[]{type},
                                                (_, method, _) -> {
                                                    throw new UnsupportedOperationException(type.getSimpleName() + "."
                                                                                            + method.getName());
                                                }));
    }

    /// Binds the ambient holder around `action`: an unruled read of it would see `context`.
    private static final class ScopedValueRun {
        static <T> T call(SecurityContext context, Supplier<T> action) {
            return ScopedValue.where(SecurityContextHolder.scopedValue(), context).call(action::get);
        }
    }

    /// The inter-slice path, as the runtime runs it: the sender's invoker (`SliceInvoker.wireCaller`/`payloadOf`) puts
    /// the caller on the invoke message as `SecurityContext.fromCallerIdentity` of the [CallerRequest]'s caller and
    /// encodes its request;
    /// the receiving bridge hands a method declaring [CallerRequest] the payload wrapped with that caller, and any
    /// other method the bare payload. Methods are resolved by name across the hosted slices.
    private static final class Wire implements SliceInvokerFacade {
        private final Map<String, SliceMethod<?, ?>> methods = new ConcurrentHashMap<>();
        private final List<Option<SecurityContext>> onTheWire = new CopyOnWriteArrayList<>();
        private final List<Object> payloads = new CopyOnWriteArrayList<>();

        void host(Slice slice) {
            slice.methods().forEach(method -> methods.put(method.name().name(), method));
        }

        SliceMethod<?, ?> method(String name) {
            return methods.get(name);
        }

        List<Option<SecurityContext>> onTheWire() {
            return onTheWire;
        }

        List<Object> payloads() {
            return payloads;
        }

        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                             String methodName,
                                                             TypeToken<T> requestType,
                                                             TypeToken<R> responseType) {
            return Result.success(new MethodHandle<>() {
                @Override
                public Promise<R> invoke(T request) {
                    return deliver(methodName, request);
                }

                @Override
                public Promise<Unit> fireAndForget(T request) {
                    return deliver(methodName, request).mapToUnit();
                }

                @Override
                public String artifactCoordinate() {
                    return sliceArtifact;
                }

                @Override
                public MethodName methodName() {
                    return MethodName.methodName(methodName).unwrap();
                }
            });
        }

        @SuppressWarnings("unchecked")
        private <R> Promise<R> deliver(String methodName, Object request) {
            var caller = request instanceof CallerRequest callerRequest
                         ? SecurityContext.fromCallerIdentity(callerRequest.caller())
                         : Option.<SecurityContext>none();
            var payload = request instanceof CallerRequest callerRequest
                          ? callerRequest.request()
                          : request;
            var target = (SliceMethod<R, Object>) methods.get(methodName);

            onTheWire.add(caller);
            payloads.add(payload);

            return CallerRequest.class.equals(target.parameterType().rawType())
                   ? target.apply(CallerRequest.callerRequest(payload, caller.map(CallerIdentity.class::cast)))
                   : target.apply(payload);
        }
    }
}
