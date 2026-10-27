package org.pragmatica.storage;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;


/// The scope a content store is bound to when it is provisioned. A store names, lists, updates and deletes inside its
/// namespace only. 1 to 63 characters of `a-z`, `0-9`, `.`, `_` and `-`, starting with a letter or digit; never a `/`,
/// so a namespace is one unambiguous path segment of every ref key.
public record Namespace(String value) {
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");

    public static Result<Namespace> namespace(String value) {
        return VALID.matcher(value).matches()
               ? Result.success(new Namespace(value))
               : ContentStoreError.InvalidConfig.invalidConfig("namespace",
                                                              "'" + value + "' must match " + VALID.pattern()).result();
    }
}
