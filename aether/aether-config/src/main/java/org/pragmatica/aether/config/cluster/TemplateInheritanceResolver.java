// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public final class TemplateInheritanceResolver {
    private TemplateInheritanceResolver() {}

    private static final String TEMPLATE_PREFIX = "template.";
    private static final String INHERIT_KEY = "inherit";
    private static final int MAX_DEPTH = 16;

    public static Result<TomlDocument> resolve(TomlDocument doc) {
        return resolveSections(doc).flatMap(sections -> propagateDescendants(doc, sections))
                              .map(finalSections -> new TomlDocument(finalSections,
                                                                     doc.tableArrays()));
    }

    private static Result<Map<String, Map<String, Object>>> propagateDescendants(TomlDocument original,
                                                                                 Map<String, Map<String, Object>> resolved) {
        var withDescendants = new LinkedHashMap<>(resolved);

        for (var sectionName : original.sectionNames()) {
            if (!isOwnerSection(sectionName)) {
                continue;
            }

            var sectionData = original.sections().get(sectionName);
            var inheritValue = sectionData.get(INHERIT_KEY);

            if (inheritValue == null) {
                continue;
            }

            var chain = resolveDescendantChain(inheritValue.toString(), original, new HashSet<>(), 0);

            if (chain.isFailure()) {
                return chain.map(_ -> withDescendants);
            }

            chain.onSuccess(templateNames -> propagateChain(original, sectionName, templateNames, withDescendants));
        }

        return success(withDescendants);
    }

    private static boolean isOwnerSection(String sectionName) {
        if (!sectionName.startsWith("source.") && !sectionName.startsWith("runtime.")) {
            return false;
        }

        var prefixLen = sectionName.startsWith("source.")
                        ? "source.".length()
                        : "runtime.".length();

        return sectionName.indexOf('.', prefixLen) < 0;
    }

    private static Result<List<String>> resolveDescendantChain(String templateName,
                                                               TomlDocument doc,
                                                               Set<String> visited,
                                                               int depth) {
        if (depth >= MAX_DEPTH) {
            return new ClusterConfigError.ParseFailed("Template inheritance depth exceeded " + MAX_DEPTH
                                                     + " for descendant chain at '" + templateName
                                                     + "' (REQ-5.3.2)").result();
        }

        var templateSection = TEMPLATE_PREFIX + templateName;

        if (!doc.hasSection(templateSection)) {
            return success(List.of());
        }

        if (!visited.add(templateName)) {
            return success(List.of());
        }

        var templateData = doc.sections().get(templateSection);
        var parentValue = templateData.get(INHERIT_KEY);

        if (parentValue == null) {
            return success(List.of(templateName));
        }

        return resolveDescendantChain(parentValue.toString(), doc, visited, depth + 1).map(parentChain -> appendTemplateName(parentChain,
                                                                                                                             templateName));
    }

    private static List<String> appendTemplateName(List<String> parentChain, String templateName) {
        var combined = new ArrayList<>(parentChain);

        combined.add(templateName);

        return List.copyOf(combined);
    }

    private static void propagateChain(TomlDocument original,
                                       String ownerSection,
                                       List<String> templateChain,
                                       Map<String, Map<String, Object>> sections) {
        for (var templateName : templateChain) {
            propagateOneTemplate(original, ownerSection, templateName, sections);
        }
    }

    private static void propagateOneTemplate(TomlDocument original,
                                             String ownerSection,
                                             String templateName,
                                             Map<String, Map<String, Object>> sections) {
        var templatePrefix = TEMPLATE_PREFIX + templateName + ".";
        var ownerPrefix = ownerSection + ".";

        for (var section : original.sectionNames()) {
            if (!section.startsWith(templatePrefix)) {
                continue;
            }

            var rest = section.substring(templatePrefix.length());
            var targetName = ownerPrefix + rest;
            var templateData = original.sections().get(section);
            var existing = sections.get(targetName);

            sections.put(targetName,
                         existing == null
                         ? Map.copyOf(templateData)
                         : mergeDescendant(templateData, existing));
        }
    }

    private static Map<String, Object> mergeDescendant(Map<String, Object> base, Map<String, Object> overlay) {
        var merged = new LinkedHashMap<>(base);

        merged.putAll(overlay);

        return Map.copyOf(merged);
    }

    private static Result<Map<String, Map<String, Object>>> resolveSections(TomlDocument doc) {
        var resolvedSections = new LinkedHashMap<String, Map<String, Object>>();

        for (var sectionName : doc.sectionNames()) {
            if (sectionName.startsWith(TEMPLATE_PREFIX)) {
                continue;
            }

            var sectionData = doc.sections().get(sectionName);

            if (!isInheriting(sectionName, sectionData)) {
                resolvedSections.put(sectionName, sectionData);
                continue;
            }

            var resolved = resolveSection(sectionName, doc, new HashSet<>(), 0);

            if (resolved.isFailure()) {
                return resolved.map(m -> resolvedSections);
            }

            resolved.onSuccess(data -> resolvedSections.put(sectionName, data));
        }

        return success(resolvedSections);
    }

    private static boolean isInheriting(String sectionName, Map<String, Object> sectionData) {
        return (sectionName.startsWith("source.") || sectionName.startsWith("runtime.")) && sectionData.containsKey(INHERIT_KEY);
    }

    private static Result<Map<String, Object>> resolveSection(String sectionName,
                                                              TomlDocument doc,
                                                              Set<String> visited,
                                                              int depth) {
        if (depth >= MAX_DEPTH) {
            return new ClusterConfigError.ParseFailed("Template inheritance depth exceeded " + MAX_DEPTH
                                                     + " for section '" + sectionName
                                                     + "' (REQ-5.3.2)").result();
        }

        var sectionData = doc.sections().get(sectionName);

        return Option.option(sectionData.get(INHERIT_KEY))
                     .map(Object::toString)
                     .fold(() -> success(sectionData),
                           templateName -> resolveWithTemplate(sectionName,
                                                               templateName,
                                                               sectionData,
                                                               doc,
                                                               visited,
                                                               depth));
    }

    private static Result<Map<String, Object>> resolveWithTemplate(String sectionName,
                                                                   String templateName,
                                                                   Map<String, Object> sectionData,
                                                                   TomlDocument doc,
                                                                   Set<String> visited,
                                                                   int depth) {
        var templateSection = TEMPLATE_PREFIX + templateName;

        if (!doc.hasSection(templateSection)) {
            return new ClusterConfigError.ParseFailed("Template '" + templateName
                                                     + "' not found for section '" + sectionName
                                                     + "' (REQ-5.3.3)").result();
        }

        if (!visited.add(templateName)) {
            return new ClusterConfigError.ParseFailed("Cyclic template inheritance detected: '" + templateName
                                                     + "' in chain for '" + sectionName
                                                     + "' (REQ-5.3.1)").result();
        }

        return resolveTemplateChain(templateSection, doc, visited, depth + 1).map(base -> mergeSectionOverBase(base,
                                                                                                               sectionData));
    }

    private static Result<Map<String, Object>> resolveTemplateChain(String templateSection,
                                                                    TomlDocument doc,
                                                                    Set<String> visited,
                                                                    int depth) {
        if (depth >= MAX_DEPTH) {
            return new ClusterConfigError.ParseFailed("Template inheritance depth exceeded " + MAX_DEPTH
                                                     + " for template '" + templateSection
                                                     + "' (REQ-5.3.2)").result();
        }

        var templateData = doc.sections().get(templateSection);

        if (!templateData.containsKey(INHERIT_KEY)) {
            return success(new HashMap<>(templateData));
        }

        var parentName = templateData.get(INHERIT_KEY).toString();
        var parentSection = TEMPLATE_PREFIX + parentName;

        if (!doc.hasSection(parentSection)) {
            return new ClusterConfigError.ParseFailed("Template '" + parentName
                                                     + "' not found for template '" + templateSection
                                                     + "' (REQ-5.3.3)").result();
        }

        if (!visited.add(parentName)) {
            return new ClusterConfigError.ParseFailed("Cyclic template inheritance detected: '" + parentName
                                                     + "' in chain for '" + templateSection
                                                     + "' (REQ-5.3.1)").result();
        }

        return resolveTemplateChain(parentSection, doc, visited, depth + 1).map(base -> mergeSectionOverBase(base,
                                                                                                             templateData));
    }

    private static Map<String, Object> mergeSectionOverBase(Map<String, Object> base, Map<String, Object> overlay) {
        var merged = new LinkedHashMap<>(base);

        overlay.entrySet().stream().filter(e -> !INHERIT_KEY.equals(e.getKey())).forEach(e -> merged.put(e.getKey(),
                                                                                                         e.getValue()));
        merged.remove(INHERIT_KEY);

        return merged;
    }
}
