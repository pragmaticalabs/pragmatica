package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Result;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Result.success;


/// Resolves `inherit` references on source and runtime profiles. REQ-5.3
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public final class TemplateInheritanceResolver {
    private TemplateInheritanceResolver() {}

    private static final String TEMPLATE_PREFIX = "template.";
    private static final String INHERIT_KEY = "inherit";
    private static final int MAX_DEPTH = 16;

    /// Resolve all `inherit` references in source and runtime sections.
    public static Result<TomlDocument> resolve(TomlDocument doc) {
        return resolveSections(doc)
            .map(sections -> new TomlDocument(sections, doc.tableArrays()));
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
        return (sectionName.startsWith("source.") || sectionName.startsWith("runtime."))
               && sectionData.containsKey(INHERIT_KEY);
    }

    private static Result<Map<String, Object>> resolveSection(String sectionName,
                                                               TomlDocument doc,
                                                               Set<String> visited,
                                                               int depth) {
        if (depth >= MAX_DEPTH) {
            return new ClusterConfigError.ParseFailed(
                "Template inheritance depth exceeded " + MAX_DEPTH + " for section '" + sectionName + "' (REQ-5.3.2)"
            ).result();
        }

        var sectionData = doc.sections().get(sectionName);
        var inheritValue = sectionData.get(INHERIT_KEY);

        if (inheritValue == null) {
            return success(sectionData);
        }

        var templateName = inheritValue.toString();
        var templateSection = TEMPLATE_PREFIX + templateName;

        if (!doc.hasSection(templateSection)) {
            return new ClusterConfigError.ParseFailed(
                "Template '" + templateName + "' not found for section '" + sectionName + "' (REQ-5.3.3)"
            ).result();
        }

        if (!visited.add(templateName)) {
            return new ClusterConfigError.ParseFailed(
                "Cyclic template inheritance detected: '" + templateName + "' in chain for '" + sectionName + "' (REQ-5.3.1)"
            ).result();
        }

        return resolveTemplateChain(templateSection, doc, visited, depth + 1)
            .map(base -> mergeSectionOverBase(base, sectionData));
    }

    private static Result<Map<String, Object>> resolveTemplateChain(String templateSection,
                                                                     TomlDocument doc,
                                                                     Set<String> visited,
                                                                     int depth) {
        if (depth >= MAX_DEPTH) {
            return new ClusterConfigError.ParseFailed(
                "Template inheritance depth exceeded " + MAX_DEPTH + " for template '" + templateSection + "' (REQ-5.3.2)"
            ).result();
        }

        var templateData = doc.sections().get(templateSection);

        if (!templateData.containsKey(INHERIT_KEY)) {
            return success(new HashMap<>(templateData));
        }

        var parentName = templateData.get(INHERIT_KEY).toString();
        var parentSection = TEMPLATE_PREFIX + parentName;

        if (!doc.hasSection(parentSection)) {
            return new ClusterConfigError.ParseFailed(
                "Template '" + parentName + "' not found for template '" + templateSection + "' (REQ-5.3.3)"
            ).result();
        }

        if (!visited.add(parentName)) {
            return new ClusterConfigError.ParseFailed(
                "Cyclic template inheritance detected: '" + parentName + "' in chain for '" + templateSection + "' (REQ-5.3.1)"
            ).result();
        }

        return resolveTemplateChain(parentSection, doc, visited, depth + 1)
            .map(base -> mergeSectionOverBase(base, templateData));
    }

    private static Map<String, Object> mergeSectionOverBase(Map<String, Object> base,
                                                             Map<String, Object> overlay) {
        var merged = new LinkedHashMap<>(base);

        overlay.entrySet()
               .stream()
               .filter(e -> !INHERIT_KEY.equals(e.getKey()))
               .forEach(e -> merged.put(e.getKey(), e.getValue()));

        merged.remove(INHERIT_KEY);
        return merged;
    }
}
