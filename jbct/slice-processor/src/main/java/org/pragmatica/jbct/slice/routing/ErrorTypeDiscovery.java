package org.pragmatica.jbct.slice.routing;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Discovers error types implementing {@link Cause} in a package
 * and maps them to HTTP status codes using pattern configuration.
 * <p>
 * Discovery process:
 * <ol>
 *   <li>Find all types in the specified package</li>
 *   <li>Filter to types implementing {@code org.pragmatica.lang.Cause}</li>
 *   <li>Match each type against patterns from configuration</li>
 *   <li>Detect conflicts (type matches multiple patterns with different statuses)</li>
 *   <li>Return mappings or error with conflicts</li>
 * </ol>
 */
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

    /**
     * Discover all error types in the package and map to HTTP status codes.
     *
     * @param packageName the package to scan for error types
     * @param config      the error pattern configuration
     * @return success with mappings, or failure with conflict details
     */
    public Result<List<ErrorTypeMapping>> discover(String packageName,
                                                   ErrorPatternConfig config) {
        if (causeType.isEmpty()) {
            return Causes.cause("Cannot resolve " + CAUSE_QUALIFIED_NAME + " - is pragmatica-lite on classpath?")
                         .result();
        }
        var errorTypes = findCauseTypes(packageName);
        if (errorTypes.isEmpty()) {
            return Result.success(List.of());
        }
        return mapErrorTypes(errorTypes, config);
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
            if (element.getKind() == ElementKind.CLASS || element.getKind() == ElementKind.ENUM || element.getKind() == ElementKind.INTERFACE) {
                var typeElement = (TypeElement) element;
                if (implementsCause(typeElement, types)) {
                    result.add(typeElement);
                    collectNestedCauseTypes(typeElement, types, result);
                }
            }
        }
        return result;
    }

    private void collectNestedCauseTypes(TypeElement enclosing,
                                         javax.lang.model.util.Types types,
                                         List<TypeElement> result) {
        for (var enclosed : enclosing.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CLASS || enclosed.getKind() == ElementKind.ENUM || enclosed.getKind() == ElementKind.INTERFACE) {
                var nested = (TypeElement) enclosed;
                if (implementsCause(nested, types)) {
                    result.add(nested);
                    collectNestedCauseTypes(nested, types, result);
                }
            }
        }
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
        var matches = findMatchingPatterns(simpleName, config.statusPatterns());
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

    private List<ErrorConflict.PatternMatch> findMatchingPatterns(String typeName,
                                                                  Map<Integer, List<String>> statusPatterns) {
        var matches = new ArrayList<ErrorConflict.PatternMatch>();
        for (var entry : statusPatterns.entrySet()) {
            var status = entry.getKey();
            for (var pattern : entry.getValue()) {
                if (ErrorTypeMatcher.matches(typeName, pattern)) {
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
