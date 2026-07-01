// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.Trees;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// Resolution of a single-source `Topic<T>` constant referenced by a pub/sub `@ResourceQualifier`
/// (#396).
///
/// A `Publisher`/`Subscriber` resource whose `config` value is an UPPER-first Java identifier
/// (e.g. `CLICK_EVENTS`) names the Java identifier of a `static final Topic<T>` constant declared
/// once in a shared interface both the publishing and subscribing slices can see. This model binds
/// that identifier to the constant's declaring holder type and its declared payload type, so the
/// generator can wrap the provisioned `Publisher` in a `TypedPublisher` and the processor can
/// reject a payload-type mismatch or an unresolved constant at compile time.
///
/// Lowercase / hyphenated `config` values (e.g. `click-events`) are the legacy resources.toml
/// section form and are left untouched — [#looksLikeConstantReference] gates the new behaviour so
/// existing slices keep building.
///
/// @param configIdentifier    the `config` string that named the constant (its Java identifier)
/// @param holderQualifiedName  fully-qualified name of the type declaring the constant (for import)
/// @param fieldName            the constant's field name
/// @param payloadTypeName      canonical name of the constant's declared `Topic<T>` payload type `T`
/// @param topicName            the topic name string extracted from the constant's `Topic.of("...", ...)`
///                             initializer, when the constant's source is in the current round;
///                             empty for an already-compiled cross-module constant (its name literal
///                             is not readable from the element model)
public record ResolvedTopicConstant(String configIdentifier,
                                    String holderQualifiedName,
                                    String fieldName,
                                    String payloadTypeName,
                                    Option<String> topicName) {
    private static final String TOPIC_TYPE = "org.pragmatica.aether.slice.topic.Topic";
    private static final String SUBSCRIPTION_CATEGORY = "subscription";
    private static final Fn1<Cause, Throwable> TREE_FAILURE = Causes::fromThrowable;
    /// An UPPER-first Java identifier signals a Topic-constant reference; a lowercase or hyphenated
    /// value is the legacy resources.toml section name and is not treated as a constant reference.
    private static final Pattern CONSTANT_REFERENCE = Pattern.compile("[A-Z][A-Za-z0-9_]*");

    /// Bindings resolved for one slice: the constants keyed by their `config` identifier (shared by
    /// publisher and subscriber references to the same topic) plus any compile diagnostics collected
    /// while resolving them. A non-empty [#errors] means the slice must not be generated.
    public record SliceTopicBindings(Map<String, ResolvedTopicConstant> bindings, List<String> errors) {}

    /// True when a `config` value is shaped like a Topic-constant identifier rather than a legacy
    /// lowercase resources.toml section name.
    public static boolean looksLikeConstantReference(String config) {
        return config != null && CONSTANT_REFERENCE.matcher(config).matches();
    }

    /// Resolve every publisher dependency and subscription method binding of a slice whose `config`
    /// names a Topic constant, scanning the current compilation round's root elements for the
    /// `static final Topic<T>` field. Records an unknown-constant diagnostic when no such field is
    /// visible and a payload-type-mismatch diagnostic when the injected `Publisher<T>` or handler
    /// parameter type does not match the constant's declared payload type.
    public static SliceTopicBindings resolveBindings(SliceModel model,
                                                     Collection<? extends Element> roots,
                                                     ProcessingEnvironment env) {
        var bindings = new LinkedHashMap<String, ResolvedTopicConstant>();
        var errors = new ArrayList<String>();
        for (var dependency : model.dependencies()) {
            resolvePublisherDependency(model, dependency, roots, env, bindings, errors);
        }
        for (var method : model.methods()) {
            resolveSubscriptionMethod(model, method, roots, env, bindings, errors);
        }
        return new SliceTopicBindings(Map.copyOf(bindings), List.copyOf(errors));
    }

    private static void resolvePublisherDependency(SliceModel model,
                                                   DependencyModel dependency,
                                                   Collection<? extends Element> roots,
                                                   ProcessingEnvironment env,
                                                   Map<String, ResolvedTopicConstant> bindings,
                                                   List<String> errors) {
        if (!dependency.isPublisher()) {
            return;
        }
        var config = dependency.resourceQualifier()
                               .map(ResourceQualifierModel::configSection)
                               .or("");
        if (!looksLikeConstantReference(config)) {
            return;
        }
        var subject = "publisher '" + dependency.parameterName() + "'";
        var injectedType = dependency.publisherMessageType()
                                     .or("");
        resolve(config, roots, env)
            .onEmpty(() -> errors.add(unknownConstantMessage(model, subject, config)))
            .onPresent(constant -> bindMatched(model, subject, config, injectedType, constant, bindings, errors));
    }

    private static void resolveSubscriptionMethod(SliceModel model,
                                                  MethodModel method,
                                                  Collection<? extends Element> roots,
                                                  ProcessingEnvironment env,
                                                  Map<String, ResolvedTopicConstant> bindings,
                                                  List<String> errors) {
        for (var binding : method.reactive()) {
            if (SUBSCRIPTION_CATEGORY.equals(binding.category())) {
                resolveSubscriptionBinding(model, method, binding.qualifier().configSection(), roots, env, bindings, errors);
            }
        }
    }

    private static void resolveSubscriptionBinding(SliceModel model,
                                                   MethodModel method,
                                                   String config,
                                                   Collection<? extends Element> roots,
                                                   ProcessingEnvironment env,
                                                   Map<String, ResolvedTopicConstant> bindings,
                                                   List<String> errors) {
        if (!looksLikeConstantReference(config)) {
            return;
        }
        var subject = "subscription method '" + method.name() + "'";
        var handlerType = subscriptionHandlerType(method);
        resolve(config, roots, env)
            .onEmpty(() -> errors.add(unknownConstantMessage(model, subject, config)))
            .onPresent(constant -> bindMatched(model, subject, config, handlerType, constant, bindings, errors));
    }

    private static String subscriptionHandlerType(MethodModel method) {
        return method.hasSingleParam()
               ? method.parameters().getFirst().type().toString()
               : "";
    }

    private static void bindMatched(SliceModel model,
                                    String subject,
                                    String config,
                                    String usageType,
                                    ResolvedTopicConstant constant,
                                    Map<String, ResolvedTopicConstant> bindings,
                                    List<String> errors) {
        if (usageType.equals(constant.payloadTypeName())) {
            bindings.put(config, constant);
        } else {
            errors.add(typeMismatchMessage(model, subject, config, usageType, constant.payloadTypeName()));
        }
    }

    /// Find the `static final Topic<T>` field named `config` among the round's root elements and
    /// their nested types.
    private static Option<ResolvedTopicConstant> resolve(String config,
                                                         Collection<? extends Element> roots,
                                                         ProcessingEnvironment env) {
        var matches = new ArrayList<VariableElement>();
        for (var root : roots) {
            collectMatches(config, root, matches);
        }
        return Option.option(matches.isEmpty() ? null : matches.getFirst())
                     .map(field -> fromField(field, env));
    }

    private static ResolvedTopicConstant fromField(VariableElement field, ProcessingEnvironment env) {
        var holder = (TypeElement) field.getEnclosingElement();
        return new ResolvedTopicConstant(field.getSimpleName().toString(),
                                         holder.getQualifiedName().toString(),
                                         field.getSimpleName().toString(),
                                         payloadTypeName(field),
                                         extractTopicName(field, env));
    }

    /// Extract the topic name literal from the constant's `Topic.of("<name>", ...)` initializer using
    /// the Compiler Tree API. Available only when the constant's source is in the current compilation
    /// round (same module / reactor); an already-compiled cross-module constant has no source tree and
    /// yields an empty name (its `Topic.of(...)` call is not a compile-time constant expression, so the
    /// element model cannot read the literal either).
    private static Option<String> extractTopicName(VariableElement field, ProcessingEnvironment env) {
        return Result.lift(TREE_FAILURE, () -> topicNameFromTree(field, env))
                     .or(Option.none());
    }

    private static Option<String> topicNameFromTree(VariableElement field, ProcessingEnvironment env) {
        var tree = Trees.instance(env).getTree(field);
        return tree instanceof VariableTree variableTree
               ? firstStringLiteral(variableTree.getInitializer())
               : Option.none();
    }

    private static Option<String> firstStringLiteral(ExpressionTree initializer) {
        if (!(initializer instanceof MethodInvocationTree invocation)) {
            return Option.none();
        }
        for (var argument : invocation.getArguments()) {
            if (argument instanceof LiteralTree literal && literal.getValue() instanceof String value) {
                return Option.some(value);
            }
        }
        return Option.none();
    }

    private static void collectMatches(String identifier, Element element, List<VariableElement> out) {
        if (!(element instanceof TypeElement typeElement)) {
            return;
        }
        for (var enclosed : typeElement.getEnclosedElements()) {
            collectFromEnclosed(identifier, enclosed, out);
        }
    }

    private static void collectFromEnclosed(String identifier, Element enclosed, List<VariableElement> out) {
        if (enclosed instanceof VariableElement field && isTopicConstant(field, identifier)) {
            out.add(field);
        }
        collectMatches(identifier, enclosed, out);
    }

    private static boolean isTopicConstant(VariableElement field, String identifier) {
        return field.getSimpleName().toString().equals(identifier)
               && field.getModifiers().contains(Modifier.STATIC)
               && field.getModifiers().contains(Modifier.FINAL)
               && isTopicType(field.asType());
    }

    private static boolean isTopicType(TypeMirror type) {
        return type instanceof DeclaredType declaredType
               && declaredType.asElement() instanceof TypeElement typeElement
               && typeElement.getQualifiedName().toString().equals(TOPIC_TYPE);
    }

    private static String payloadTypeName(VariableElement field) {
        return field.asType() instanceof DeclaredType declaredType && !declaredType.getTypeArguments().isEmpty()
               ? declaredType.getTypeArguments().getFirst().toString()
               : "";
    }

    private static String unknownConstantMessage(SliceModel model, String subject, String config) {
        return "Slice " + model.simpleName() + ": @ResourceQualifier(config = \"" + config + "\") on " + subject
               + " names no visible `static final Topic<?> " + config + "` constant. Declare the Topic constant"
               + " in a shared interface both the publishing and subscribing slices can see, or use a lowercase"
               + " topic-section name for the legacy resources.toml form.";
    }

    private static String typeMismatchMessage(SliceModel model,
                                              String subject,
                                              String config,
                                              String usageType,
                                              String payloadType) {
        return "Slice " + model.simpleName() + ": " + subject + " binds payload type '" + usageType
               + "' to Topic constant '" + config + "' whose declared payload type is '" + payloadType
               + "'. The injected Publisher or handler payload type must match the Topic's payload type.";
    }
}
