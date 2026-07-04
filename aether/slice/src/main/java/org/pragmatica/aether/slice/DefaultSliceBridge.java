// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Option.option;


@SuppressWarnings("JBCT-NAM-01")
public record DefaultSliceBridge(Artifact artifact,
                                 Slice slice,
                                 Map<String, InternalMethod> methodMap,
                                 SliceCodec codec) implements SliceBridge {
    public record InternalMethod(SliceMethod<?, ?> method,
                                 TypeToken<?> parameterType,
                                 TypeToken<?> returnType,
                                 ObservabilityStrategyCell cell) {}

    public static DefaultSliceBridge defaultSliceBridge(Artifact artifact, Slice slice, SliceCodec codec) {
        var artifactBase = artifact.base()
                                   .asString();
        var methodMap = slice.methods().stream().collect(Collectors.toMap(m -> m.name()
                                                                                .name(),
                                                                          m -> new InternalMethod(m,
                                                                                                  m.parameterType(),
                                                                                                  m.returnType(),
                                                                                                  ObservabilityStrategyCell.observabilityStrategyCell(artifactBase,
                                                                                                                                                      m.name()
                                                                                                                                                       .name()))));

        return new DefaultSliceBridge(artifact, slice, Map.copyOf(methodMap), codec);
    }

    @Override
    public Option<ObservabilityStrategyCell> observabilityCell(String methodName) {
        return option(methodMap.get(methodName)).map(InternalMethod::cell);
    }

    @Override
    public List<ObservabilityStrategyCell> observabilityCells() {
        return methodMap.values()
                        .stream()
                        .map(InternalMethod::cell)
                        .toList();
    }

    @Override
    public Promise<byte[]> invoke(String methodName, byte[] input) {
        return lookupMethod(methodName).async()
                           .flatMap(method -> invokeWithSerialization(method, input));
    }

    @Override
    public Promise<byte[]> encode(Object input) {
        return Promise.lift(Causes::fromThrowable, () -> codec.encode(input));
    }

    @Override
    public Promise<Object> decode(byte[] bytes) {
        return Promise.lift(Causes::fromThrowable, () -> (Object) codec.decode(bytes));
    }

    @Override
    public ClassLoader classLoader() {
        return slice.getClass()
                    .getClassLoader();
    }

    private Promise<byte[]> invokeWithSerialization(InternalMethod method, byte[] input) {
        return deserializeInput(input).flatMap(parameter -> invokeAndSerialize(method.method(), parameter));
    }

    private <T> Promise<byte[]> invokeAndSerialize(SliceMethod<?, ?> method, T parameter) {
        return invokeMethod(method, parameter).flatMap(this::serializeResponse);
    }

    @Override
    public Promise<Unit> start() {
        return slice.start();
    }

    @Override
    public Promise<Unit> stop() {
        return slice.stop();
    }

    @Override
    public List<String> methodNames() {
        return List.copyOf(methodMap.keySet());
    }

    private Result<InternalMethod> lookupMethod(String methodName) {
        return option(methodMap.get(methodName)).toResult(METHOD_NOT_FOUND.apply(methodName));
    }

    @SuppressWarnings("unchecked")
    private <T> Promise<T> deserializeInput(byte[] input) {
        return Promise.lift(Causes::fromThrowable, () -> (T) codec.decode(input));
    }

    @SuppressWarnings("unchecked")
    private <T, R> Promise<R> invokeMethod(SliceMethod<?, ?> method, T parameter) {
        return Promise.lift(Causes::fromThrowable,
                            () -> ((SliceMethod<R, T>) method).apply(parameter))
                      .flatMap(promise -> promise);
    }

    private <R> Promise<byte[]> serializeResponse(R response) {
        return Promise.lift(Causes::fromThrowable, () -> codec.encode(response));
    }

    private static final Fn1<Cause, String> METHOD_NOT_FOUND = Causes.forOneValue("Method not found: {0}");
}
