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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HandlerCodecs;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityCodecs;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.slice.CallerIdentity;
import org.pragmatica.aether.slice.CallerRequest;
import org.pragmatica.aether.slice.DefaultSliceBridge;
import org.pragmatica.aether.slice.JsonCodecSettings;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.ProvisioningContext;
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
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2157 part b, against the GENERATED factories and router: the caller's [SecurityContext], exactly as the edge
/// validated it, reaches a slice method's explicit `SecurityContext`/`Principal` parameter at the edge, one hop and
/// two hops away. Between slices it travels beside the payload, the way the invoker carries it on the invoke message
/// ([Wire] reproduces that path through the real codecs: the sender slice's codec encodes the payload, the caller crosses
/// as encoded bytes, and the callee's real [DefaultSliceBridge] decodes both). An unauthenticated caller travels as "no
/// identity"; nothing is read from ambient state.
class GeneratedCallerPropagationTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final SecurityContext ALICE = SecurityContext.securityContext(Principal.principal("user:alice").unwrap(),
                                                                                 Set.of(Role.role("auditor").unwrap()),
                                                                                 Map.of("token", "secret-jwt"),
                                                                                 AuthorizationRole.VIEWER);
    private static final SecurityContext MALLORY = SecurityContext.securityContext("mallory", Set.of(Role.ADMIN)).unwrap();
    private static final String NOTE_JSON = "{\"text\":\"t\"}";

    private static final RequestHeaders PROBE_HEADERS = RequestHeaders.requestHeaders(Map.of("X-Probe", List.of("p")));
    /// The codec every slice codec layers over in a node, reduced to what these slices' payloads and the caller need:
    /// the framework types, `RequestHeaders`, and the security types.
    private static final SliceCodec PARENT = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(),
                                                                   Stream.concat(HandlerCodecs.CODECS.stream(),
                                                                                 SecurityCodecs.CODECS.stream())
                                                                         .toList());

    private final Wire wire = new Wire();
    private SliceRouter router;
    private MidSlice mid;

    @BeforeEach
    void setUp() {
        var midCodec = new AtomicReference<SliceCodec>();
        var edgeCodec = new AtomicReference<SliceCodec>();

        wire.host("com.example:caller-leaf:1.0.0", LeafSliceFactory.leafSliceSlice(context(wire.sentBy(() -> PARENT))).await().unwrap());
        midCodec.set(wire.host("com.example:caller-mid:1.0.0",
                               MidSliceFactory.midSliceSlice(context(wire.sentBy(midCodec::get))).await().unwrap()));
        edgeCodec.set(EdgeSliceFactory.edgeSliceSlice(SliceCreationContext.sliceCreationContext(wire.sentBy(edgeCodec::get),
                                                                                                 JSON_SETTINGS_ONLY))
                                      .await()
                                      .unwrap()
                                      .codec(PARENT));
        mid = MidSliceFactory.midSlice(context(wire.sentBy(midCodec::get))).await().unwrap();
        router = new EdgeSliceRoutes().create(EdgeSliceFactory.edgeSlice(context(wire.sentBy(edgeCodec::get))).await().unwrap(),
                                              JsonMapper.defaultJsonMapper());
    }

    /// The edge slice (it has routes) asks its resources for its JSON codec settings; nothing else is provided.
    private static final ResourceProviderFacade JSON_SETTINGS_ONLY = new ResourceProviderFacade() {
        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return Promise.success(resourceType.cast(JsonCodecSettings.defaults()));
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
            return provide(resourceType, configSection);
        }

        @Override
        public Promise<Unit> releaseAll(String sliceId) {
            return Promise.unitPromise();
        }
    };

    private static SliceCreationContext context(SliceInvokerFacade invoker) {
        return SliceCreationContext.sliceCreationContext(invoker, unused(ResourceProviderFacade.class));
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

    /// #2157 A6-1: two payload parameters beside the context. The caller's proxy record is encoded by the caller's codec
    /// under the tag of the callee's wire record, so the callee decodes its own record from the caller's bytes.
    @Test
    void oneHop_headersAndANoteBesideTheContext_crossTheCodecAsTheCalleesWireRecord() {
        var seen = await(mid.relayWithHeaders(ALICE, PROBE_HEADERS, new Note("h")));

        assertThat(seen).isEqualTo(new Seen("user:alice", List.of("auditor"), "h>leaf:probe=p"));
        assertThat(wire.onTheWire()).containsExactly(Option.some(ALICE));
        assertThat(wire.payloads()).singleElement().isInstanceOf(LeafSliceFactory.WithHeadersWireRequest.class);
    }

    /// The same encoding serves a two-parameter call that carries no caller: a `Principal` beside a business record stays
    /// in the payload, which the callee decodes as its own request record.
    @Test
    void oneHop_principalBesideANote_withoutAContext_crossesTheCodecAsTheCalleesRequestRecord() {
        var seen = await(mid.relayPrincipalOnly(ALICE.principal(), new Note("p")));

        assertThat(seen).isEqualTo(new Seen("user:alice", List.of(), "p>mid>leaf"));
        assertThat(wire.onTheWire()).containsExactly(Option.none());
        assertThat(wire.payloads()).singleElement().isInstanceOf(LeafSliceFactory.PrincipalOnlyRequest.class);
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
        var payload = new LeafSliceFactory.WithHeadersWireRequest(PROBE_HEADERS, new Note("h"));

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
            .as("a one-parameter payload needs no proxy record")
            .doesNotContain("record leaf_WhoAmIRequest")
            .doesNotContain("record leaf_BothRequest")
            .contains("withHeadersHandle.invoke(CallerRequest.callerRequest(new leaf_WithHeadersRequest(")
            .as("the caller encodes its payload records under the callee's record tags")
            .contains("SliceCodec.deterministicTag(\"com.example.callerleaf.LeafSliceFactory.WithHeadersWireRequest\")")
            .contains("SliceCodec.deterministicTag(\"com.example.callerleaf.LeafSliceFactory.PrincipalOnlyRequest\")");
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

    /// The inter-slice path between nodes, as the runtime runs it, with the real codecs on both ends. The sender's
    /// invoker (`SliceInvoker.wireCaller`/`payloadOf`) puts `SecurityContext.fromCallerIdentity` of the
    /// [CallerRequest]'s caller on the invoke message and encodes the request with the sending slice's codec; here the
    /// caller crosses as bytes of its own codec (the one `InvokeRequest.caller` is written with). The receiving node hands
    /// the bytes and the caller to the callee's [DefaultSliceBridge], which decodes the payload with the callee's codec
    /// and wraps it with the caller for a method declaring [CallerRequest]; the response returns as bytes the sender
    /// decodes. Methods are resolved by name across the hosted slices.
    private static final class Wire {
        private final Map<String, Hosted> methods = new ConcurrentHashMap<>();
        private final List<Option<SecurityContext>> onTheWire = new CopyOnWriteArrayList<>();
        private final List<Object> payloads = new CopyOnWriteArrayList<>();

        private record Hosted(SliceMethod<?, ?> method, DefaultSliceBridge bridge, SliceCodec codec) {}

        /// Hosts `slice` behind its own bridge and codec; returns the codec.
        SliceCodec host(String artifact, Slice slice) {
            var codec = slice.codec(PARENT);
            var bridge = DefaultSliceBridge.defaultSliceBridge(Artifact.artifact(artifact).unwrap(), slice, codec);

            slice.methods().forEach(method -> methods.put(method.name().name(), new Hosted(method, bridge, codec)));

            return codec;
        }

        SliceMethod<?, ?> method(String name) {
            return methods.get(name).method();
        }

        List<Option<SecurityContext>> onTheWire() {
            return onTheWire;
        }

        List<Object> payloads() {
            return payloads;
        }

        /// The invoker of a slice whose requests are encoded with `senderCodec`.
        SliceInvokerFacade sentBy(Supplier<SliceCodec> senderCodec) {
            return new SliceInvokerFacade() {
                @Override
                public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                     String methodName,
                                                                     TypeToken<T> requestType,
                                                                     TypeToken<R> responseType) {
                    return Result.success(handle(senderCodec, sliceArtifact, methodName));
                }
            };
        }

        private <R, T> MethodHandle<R, T> handle(Supplier<SliceCodec> senderCodec, String sliceArtifact, String methodName) {
            return new MethodHandle<>() {
                @Override
                public Promise<R> invoke(T request) {
                    return deliver(senderCodec.get(), methodName, request);
                }

                @Override
                public Promise<Unit> fireAndForget(T request) {
                    return deliver(senderCodec.get(), methodName, request).mapToUnit();
                }

                @Override
                public String artifactCoordinate() {
                    return sliceArtifact;
                }

                @Override
                public MethodName methodName() {
                    return MethodName.methodName(methodName).unwrap();
                }
            };
        }

        private <R> Promise<R> deliver(SliceCodec senderCodec, String methodName, Object request) {
            var target = methods.get(methodName);

            return Promise.lift(Causes::fromThrowable, () -> send(senderCodec, target, request))
                          .flatMap(sent -> target.bridge().invokeAsCaller(methodName, sent.payload(), sent.caller()))
                          .flatMap(response -> Promise.lift(Causes::fromThrowable, () -> senderCodec.<R>decode(response)));
        }

        private record Sent(byte[] payload, Option<CallerIdentity> caller) {}

        private Sent send(SliceCodec senderCodec, Hosted target, Object request) {
            var caller = request instanceof CallerRequest callerRequest
                         ? SecurityContext.fromCallerIdentity(callerRequest.caller())
                         : Option.<SecurityContext>none();
            var payload = request instanceof CallerRequest callerRequest
                          ? callerRequest.request()
                          : request;
            var payloadBytes = senderCodec.encode(payload);
            Option<SecurityContext> received = PARENT.decode(PARENT.encode(caller));

            onTheWire.add(received);
            payloads.add(target.codec().decode(payloadBytes));

            return new Sent(payloadBytes, received.map(CallerIdentity.class::cast));
        }
    }
}
