package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.Map;

import static org.pragmatica.lang.Result.success;

/// Tag/label selector for filtering compute instances.
/// Matches instances whose tags contain all required entries.
public record TagSelector(Map<String, String> requiredTags) {
    public static Result<TagSelector> tagSelector(Map<String, String> requiredTags) {
        return success(new TagSelector(Map.copyOf(requiredTags)));
    }

    public boolean matches(InstanceInfo instance) {
        return instance.tags()
                       .entrySet()
                       .containsAll(requiredTags.entrySet());
    }
}
