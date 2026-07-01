// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.routing;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Discovers error types implementing {@link Cause} in a package
/// and maps them to HTTP status codes using pattern configuration.
///
/// Discovery process:
/// <ol>
///   - Find all types in the specified package
///   - Filter to types implementing `org.pragmatica.lang.Cause`
///   - Match each type against patterns from configuration
///   - Detect conflicts (type matches multiple patterns with different statuses)
///   - Return mappings or error with conflicts
/// </ol>
public final class ErrorTypeDiscovery {
    private static final String CAUSE_QUALIFIED_NAME = "org.pragmatica.lang.Cause";

    private final ProcessingEnvironment processingEnv;
    private final Option<TypeMirror> causeType;

    public ErrorTypeDiscovery(ProcessingEnvironment processingEnv) {
        this.processingEnv = processingEnv;
        this.causeType = resolveCauseType();
    }

    private Option<TypeMirror> resolveCauseType() {
        return Option.option(processingEnv.getElementUtils()
                                          .getTypeElement(CAUSE_QUALIFIED_NAME))
                     .map(TypeElement::asType);
    }

    /// Discover all error types in the package, map to HTTP status codes, and validate totality.
    ///
    /// @param packageName   the package to scan for error types
    /// @param config        the error pattern configuration
    /// @param routesTomlPath the resource path of the `routes.toml`, named in validation messages
    /// @return success with mappings + validation issues, or failure with conflict details
    public Result<DiscoveryResult> discover(String packageName,
                                            ErrorPatternConfig config,
                                            String routesTomlPath) {
        if (causeType.isEmpty()) {
            return Causes.cause("Cannot resolve " + CAUSE_QUALIFIED_NAME + " - is pragmatica-lite on classpath?")
                         .result();
        }
        var errorTypes = findCauseTypes(packageName);
        if (errorTypes.isEmpty()) {
            return Result.success(new DiscoveryResult(List.of(), List.of()));
        }
        return mapErrorTypes(errorTypes, config)
            .map(mappings -> new DiscoveryResult(mappings, validate(errorTypes, config, routesTomlPath)));
    }

    /// Mappings for the router plus the totality / dead-mapping issues found for this slice.
    ///
    /// @param mappings the error type to HTTP status mappings fed to the router generator
    /// @param issues   the totality / dead-mapping problems, reported via the Messager by the caller
    public record DiscoveryResult(List<ErrorTypeMapping> mappings, List<ErrorMappingValidator.Issue> issues) {}

    private List<ErrorMappingValidator.Issue> validate(List<TypeElement> errorTypes,
                                                       ErrorPatternConfig config,
                                                       String routesTomlPath) {
        var descriptors = errorTypes.stream()
                                    .map(ErrorTypeDiscovery::toDescriptor)
                                    .toList();
        return ErrorMappingValidator.validate(descriptors, config, routesTomlPath);
    }

    private static ErrorMappingValidator.CauseDescriptor toDescriptor(TypeElement element) {
        return new ErrorMappingValidator.CauseDescriptor(element.getSimpleName()
                                                                .toString(),
                                                         element.getQualifiedName()
                                                                .toString(),
                                                         isLeaf(element));
    }

    /// A Cause type is a mappable leaf when it can actually be returned as a value: records and
    /// enums (the concrete failure carriers) and non-abstract classes. The sealed interface itself
    /// and abstract classes are catch-all supertypes, never returned directly, so they are exempt
    /// from the totality check.
    private static boolean isLeaf(TypeElement element) {
        return switch (element.getKind()) {
            case RECORD, ENUM -> true;
            case CLASS -> !element.getModifiers()
                                  .contains(Modifier.ABSTRACT);
            default -> false;
        };
    }

    private List<TypeElement> findCauseTypes(String packageName) {
        var packageElement = processingEnv.getElementUtils()
                                          .getPackageElement(packageName);
        if (packageElement == null) {
            return List.of();
        }
        var types = processingEnv.getTypeUtils();
        var result = new ArrayList<TypeElement>();
        for (var element : packageElement.getEnclosedElements()) {
            if (isTypeKind(element.getKind())) {
                var typeElement = (TypeElement) element;
                if (implementsCause(typeElement, types)) {
                    result.add(typeElement);
                }
                // Always recurse - Cause types may be nested inside non-Cause types (e.g., @Slice interfaces)
                collectNestedCauseTypes(typeElement, types, result);
            }
        }
        return result;
    }

    private void collectNestedCauseTypes(TypeElement enclosing,
                                         javax.lang.model.util.Types types,
                                         List<TypeElement> result) {
        for (var enclosed : enclosing.getEnclosedElements()) {
            if (isTypeKind(enclosed.getKind())) {
                var nested = (TypeElement) enclosed;
                if (implementsCause(nested, types)) {
                    result.add(nested);
                }
                collectNestedCauseTypes(nested, types, result);
            }
        }
    }

    private static boolean isTypeKind(ElementKind kind) {
        return kind == ElementKind.CLASS || kind == ElementKind.ENUM || kind == ElementKind.INTERFACE || kind == ElementKind.RECORD;
    }

    private boolean implementsCause(TypeElement element, javax.lang.model.util.Types types) {
        return causeType.map(ct -> types.isAssignable(element.asType(),
                                                      ct))
                        .or(false);
    }

    private Result<List<ErrorTypeMapping>> mapErrorTypes(List<TypeElement> errorTypes,
                                                         ErrorPatternConfig config) {
        var mappings = new ArrayList<ErrorTypeMapping>();
        var conflicts = new ArrayList<ErrorConflict>();
        for (var errorType : errorTypes) {
            var simpleName = errorType.getSimpleName()
                                      .toString();
            var mappingResult = resolveMapping(errorType, simpleName, config);
            mappingResult.onSuccess(mappings::add)
                         .onFailure(cause -> {
                             if (cause instanceof ConflictCause cc) {
                                 conflicts.add(cc.conflict());
                             }
                         });
        }
        if (!conflicts.isEmpty()) {
            return formatConflictError(conflicts).result();
        }
        // Sort children before parents for correct switch pattern dominance
        var types = processingEnv.getTypeUtils();
        mappings.sort((a, b) -> {
                          var aType = a.errorType()
                                       .asType();
                          var bType = b.errorType()
                                       .asType();
                          if (types.isAssignable(aType, bType)) {
                              return - 1;
                          }
                          if (types.isAssignable(bType, aType)) {
                              return 1;
                          }
                          return 0;
                      });
        return Result.success(List.copyOf(mappings));
    }

    private Result<ErrorTypeMapping> resolveMapping(TypeElement errorType,
                                                    String simpleName,
                                                    ErrorPatternConfig config) {
        var explicit = config.explicitMappings()
                             .get(simpleName);
        if (explicit != null) {
            return Result.success(ErrorTypeMapping.errorTypeMapping(errorType, explicit));
        }
        var qualifiedName = errorType.getQualifiedName()
                                     .toString();
        var matches = findMatchingPatterns(simpleName, qualifiedName, config.statusPatterns());
        if (matches.isEmpty()) {
            // No pattern matched - use default status with no pattern
            return Result.success(ErrorTypeMapping.errorTypeMapping(errorType, config.defaultStatus()));
        }
        if (matches.size() == 1) {
            var match = matches.getFirst();
            return Result.success(ErrorTypeMapping.errorTypeMapping(errorType, match.status(), match.pattern()));
        }
        var allSameStatus = matches.stream()
                                   .map(ErrorConflict.PatternMatch::status)
                                   .distinct()
                                   .count() == 1;
        if (allSameStatus) {
            var match = matches.getFirst();
            return Result.success(ErrorTypeMapping.errorTypeMapping(errorType, match.status(), match.pattern()));
        }
        return new ConflictCause(ErrorConflict.errorConflict(errorType, matches)).result();
    }

    private List<ErrorConflict.PatternMatch> findMatchingPatterns(String simpleName,
                                                                  String qualifiedName,
                                                                  Map<Integer, List<String>> statusPatterns) {
        var matches = new ArrayList<ErrorConflict.PatternMatch>();
        for (var entry : statusPatterns.entrySet()) {
            var status = entry.getKey();
            for (var pattern : entry.getValue()) {
                if (ErrorTypeMatcher.matchesType(simpleName, qualifiedName, pattern)) {
                    matches.add(new ErrorConflict.PatternMatch(pattern, status));
                }
            }
        }
        return matches;
    }

    private Cause formatConflictError(List<ErrorConflict> conflicts) {
        var messages = conflicts.stream()
                                .map(ErrorConflict::errorMessage)
                                .toList();
        return Causes.cause("Error type mapping conflicts:\n\n" + String.join("\n\n", messages));
    }

    private record ConflictCause(ErrorConflict conflict) implements Cause {
        @Override
        public String message() {
            return conflict.errorMessage();
        }
    }
}
